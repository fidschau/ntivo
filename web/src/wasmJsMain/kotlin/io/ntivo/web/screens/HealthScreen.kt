package io.ntivo.web.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import kotlinx.coroutines.launch

@Composable
fun HealthScreen(
    onHealthResult: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var resultState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Server Health Check",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Verify the Ktor server is running and responding on localhost:8080.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                resultState = ResultState.Loading
                scope.launch {
                    try {
                        val response = NtivoApiClient.checkHealth()
                        resultState = ResultState.Success("Status: ${response.status}")
                        onHealthResult(response.status == "ok")
                    } catch (e: Exception) {
                        resultState = ResultState.Error(
                            "Failed to reach server.\n\n" +
                                "Error: ${e.message}\n\n" +
                                "Make sure the server is running:\n" +
                                "  ./gradlew :server:run"
                        )
                        onHealthResult(false)
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Check Health")
        }

        Spacer(Modifier.height(20.dp))

        ResultPanel(
            state = resultState,
            label = "Response"
        )
    }
}
