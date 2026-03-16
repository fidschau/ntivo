package io.ntivo.web.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ntivo.web.theme.NtivoError

sealed class ResultState {
    data object Empty : ResultState()
    data object Loading : ResultState()
    data class Success(val content: String) : ResultState()
    data class Error(val message: String) : ResultState()
}

@Composable
fun ResultPanel(
    state: ResultState,
    modifier: Modifier = Modifier,
    label: String = "Result"
) {
    val shape = RoundedCornerShape(8.dp)

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 400.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(16.dp)
        ) {
            when (state) {
                is ResultState.Empty -> {
                    Text(
                        text = "No results yet. Try making a request.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                is ResultState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(32.dp)
                            .align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                }

                is ResultState.Success -> {
                    SelectionContainer {
                        Text(
                            text = state.content,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }

                is ResultState.Error -> {
                    SelectionContainer {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = NtivoError,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}
