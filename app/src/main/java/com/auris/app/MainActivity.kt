package com.auris.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.auris.app.ui.AurisApp
import com.auris.app.ui.extensions.ExtensionsViewModel
import com.auris.app.ui.theme.AurisTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as AurisApplication).container

        setContent {
            AurisTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background,
                ) {
                    AurisApp(
                        repository = container.libraryRepository,
                        pendingSyncCount = container.libraryRepository.observePendingSyncCount(),
                        libraryRepository = container.libraryRepository,
                        kitsuIdentifier = container.kitsuIdentifier,
                        extensionContentRepository = container.extensionContentRepository,
                        episodePlayer = container.episodePlayer,
                        appStateCollector = container.appStateCollector,
                        innerTubeClient = container.innerTubeClient,
                        extensionRepository = container.extensionRepository,
                        extensionsViewModelFactory = ExtensionsViewModel.Factory(
                            context = applicationContext,
                            client = container.extensionRepositoryClient,
                            extensionRepository = container.extensionRepository,
                            extensionLoader = container.extensionLoader,
                            extensionsDir = container.extensionsDir,
                        ),
                        // Reached from Settings via the nav bar's settings affordance.
                        extensionRepo = com.auris.app.source.repo.ExtensionRepo.DEFAULT,
                    )
                }
            }
        }
    }
}