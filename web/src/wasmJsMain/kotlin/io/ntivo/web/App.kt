package io.ntivo.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.ntivo.web.components.NtivoHeader
import io.ntivo.web.components.NtivoTab
import io.ntivo.web.components.NtivoTabs
import io.ntivo.web.screens.*
import io.ntivo.web.theme.NtivoTheme

@Composable
fun App() {
    NtivoTheme {
        var selectedTab by remember { mutableStateOf(NtivoTab.HEALTH) }
        var isHealthy by remember { mutableStateOf<Boolean?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            NtivoHeader(isHealthy = isHealthy)

            NtivoTabs(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )

            // Scrollable content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                when (selectedTab) {
                    NtivoTab.HEALTH -> HealthScreen(
                        onHealthResult = { isHealthy = it }
                    )
                    NtivoTab.CHAT -> ChatScreen()
                    NtivoTab.EMBED -> EmbedScreen()
                    NtivoTab.SEARCH -> SearchScreen()
                    NtivoTab.PARSE -> ParseScreen()
                }
            }
        }
    }
}
