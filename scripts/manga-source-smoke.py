"""Read-only MangaDex smoke check; optionally saves JSON fixtures outside the source tree."""
import json
import urllib.parse
import urllib.request
import pathlib
import time

def get(path, params=None):
    url = 'https://api.mangadex.org/' + path
    if params:
        url += '?' + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={'User-Agent': 'Auris/0.1.0', 'Accept': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
    assert result['result'] == 'ok'
    return result

params = [('limit', '30'), ('offset', '0'), ('includes[]', 'cover_art'), ('includes[]', 'author'),
          ('availableTranslatedLanguage[]', 'en'), ('hasAvailableChapters', 'true'),
          ('contentRating[]', 'safe'), ('contentRating[]', 'suggestive'), ('order[followedCount]', 'desc')]
catalogue = get('manga', params)
print('Catalogue:', len(catalogue['data']), 'results', flush=True)
assert catalogue['data']
search = get('manga', [p for p in params if p[0] != 'order[followedCount]'] + [('title', 'Azumanga'), ('order[relevance]', 'desc')])
print('Search:', len(search['data']), 'results', flush=True)
latest = get('manga', [(k.replace('followedCount', 'latestUploadedChapter'), v) for k, v in params])
print('Latest:', len(latest['data']), 'results', flush=True)
for manga in search['data'] + catalogue['data'][:5]:
    feed = get('manga/' + manga['id'] + '/feed', [
        ('translatedLanguage[]', 'en'), ('includeExternalUrl', '0'), ('includeEmptyPages', '0'),
        ('includeFuturePublishAt', '0'), ('order[volume]', 'asc'), ('order[chapter]', 'asc'), ('limit', '500'), ('offset', '0')])
    chapters = [c for c in feed['data'] if c['attributes']['pages'] > 0 and not c['attributes'].get('externalUrl')]
    if not chapters:
        continue
    print('Chapter feed:', len(chapters), 'readable chapters;', next(iter(manga['attributes']['title'].values())), flush=True)
    pages = get('at-home/server/' + chapters[0]['id'])
    files = pages['chapter']['data']
    assert files
    image = pages['baseUrl'] + '/data/' + pages['chapter']['hash'] + '/' + files[0]
    with urllib.request.urlopen(urllib.request.Request(image, headers={'User-Agent': 'Auris/0.1.0'}), timeout=30) as response:
        prefix = response.read(32)
        content_type = response.headers.get('Content-Type', '')
        assert content_type.startswith('image/'), content_type
        print('First page:', response.status, content_type, 'image bytes received', flush=True)
    target = pathlib.Path(__file__).resolve().parents[1] / '.toolchain' / 'manga-smoke'
    target.mkdir(parents=True, exist_ok=True)
    for name, data in [('catalogue', catalogue), ('feed', feed), ('pages', pages)]:
        (target / (name + '.json')).write_text(json.dumps(data), encoding='utf-8')
    print('PASS: catalogue, search, latest, chapter feed, image resolution, image download.', flush=True)
    break
else:
    raise RuntimeError('No readable English chapter found in sampled titles')
