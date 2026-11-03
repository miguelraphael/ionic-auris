"""Read-only live source check. No episode is downloaded in full; sample at most 64 KiB."""
import base64
import html
import json
import pathlib
import re
import time
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1] / '.toolchain' / 'anime-smoke'
ROOT.mkdir(parents=True, exist_ok=True)
AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

def get(url, name, referer='https://hianime.at/', binary=False):
    headers = {'User-Agent': AGENT, 'Referer': referer}
    if binary:
        headers['Range'] = 'bytes=0-65535'
    started = time.monotonic()
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        first_byte = time.monotonic() - started
        data = response.read(65536 if binary else 3000000)
        print(name, response.status, response.headers.get('Content-Type'), len(data),
              'bytes; headers after', round(first_byte, 2), 's; total', round(time.monotonic() - started, 2), 's', flush=True)
    (ROOT / name).write_bytes(data)
    return data if binary else data.decode('utf-8')

for path, name in [('most-popular', 'popular.html'), ('recently-updated', 'latest.html'),
                   ('search?keyword=naruto', 'search.html')]:
    page = get('https://hianime.at/' + path, name)
    cards = re.findall(r'<h3 class="film-name">\s*<a[^>]*href="([^"]+)"[^>]*title="([^"]+)"', page)
    assert cards, 'No catalogue cards in ' + path

slug = cards[0][0].rstrip('/').split('/')[-1]
episodes = json.loads(get('https://hianime.at/api/theme/episode/list/' + slug.split('-')[-1], 'episodes.json'))
assert episodes['status'] is True
ids = re.findall(r'class="[^"]*ep-item[^"]*"[^>]*data-number="([^"]+)"[^>]*data-id="(\d+)"', episodes['html'])
assert ids
print('Title:', html.unescape(cards[0][1]), ';', len(ids), 'episode entries', flush=True)
servers = json.loads(get('https://hianime.at/api/theme/episode/servers?episodeId=' + ids[0][1], 'servers.json'))
assert servers['status'] is True
tags = re.findall(r'<div[^>]*class="[^"]*server-item[^"]*"[^>]*>', servers['html'])
tag = next(tag for tag in tags if 'data-type="sub"' in tag and 'data-server-name="ZokoAnime"' in tag)
embed = base64.b64decode(re.search(r'data-hash="([^"]+)"', tag).group(1)).decode('utf-8')
page = get(embed, 'embed.html')
blob = base64.b64decode(re.search(r'window\.__P\s*=\s*"([^"]+)"', page).group(1))
key = b'otaku-embed-v1'
config = json.loads(bytes(value ^ key[i % len(key)] for i, value in enumerate(blob)))
(ROOT / 'embed-config.json').write_text(json.dumps(config), encoding='utf-8')
referer = urllib.parse.urlsplit(embed)._replace(path='/', query='', fragment='').geturl()
master = get(config['src'], 'master.m3u8', referer)
assert master.startswith('#EXTM3U')
variant = urllib.parse.urljoin(config['src'], next(line for line in master.splitlines() if line.strip() and not line.startswith('#')))
media = get(variant, 'media.m3u8', referer)
assert media.startswith('#EXTM3U')
segment = urllib.parse.urljoin(variant, next(line for line in media.splitlines() if line.strip() and not line.startswith('#')))
sample = get(segment, 'segment.bin', referer, binary=True)
assert sample and sample[0] == 0x47, 'Expected MPEG-TS sync byte'
if config.get('subtitles'):
    track = next((t for t in config['subtitles'] if t.get('default')), config['subtitles'][0])
    subtitle = get(track['src'], 'subtitles.vtt', referer)
    assert subtitle.lstrip('\ufeff').startswith('WEBVTT')
print('PASS: browse, latest, search, episodes, server resolution, HLS, video bytes, subtitles.', flush=True)
print('Timings describe this machine and this request only; they are not a server-speed guarantee.', flush=True)
