package eu.kanade.presentation.more.settings.screen.browse

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Manages the WebView ad-blocker's filter-list URLs (add/remove), backed by
 * [SourcePreferences.webViewAdblockFilterUrls].
 */
class AdblockFilterListsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val pref = remember { Injekt.get<SourcePreferences>().webViewAdblockFilterUrls() }
        val urls by pref.collectAsState()
        val lazyListState = rememberLazyListState()
        var showAddDialog by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = "Ad-block filter lists",
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                CategoryFloatingActionButton(
                    lazyListState = lazyListState,
                    onCreate = { showAddDialog = true },
                )
            },
        ) { paddingValues ->
            if (urls.isEmpty()) {
                EmptyScreen(
                    message = "No filter lists. Add a blocklist URL to start blocking ads.",
                    modifier = Modifier.padding(paddingValues),
                )
                return@Scaffold
            }
            ScrollbarLazyColumn(
                state = lazyListState,
                contentPadding = paddingValues,
            ) {
                items(urls.sorted(), key = { it }) { url ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = url, modifier = Modifier.weight(1f))
                        IconButton(onClick = { pref.set(urls - url) }) {
                            Icon(imageVector = Icons.Outlined.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            var input by remember { mutableStateOf("") }
            val trimmed = input.trim()
            val invalid = trimmed.isNotEmpty() &&
                !(trimmed.startsWith("http://") || trimmed.startsWith("https://"))
            val duplicate = trimmed in urls
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add filter list") },
                text = {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Blocklist URL") },
                        supportingText = {
                            Text(
                                when {
                                    invalid -> "Must start with http:// or https://"
                                    duplicate -> "Already added"
                                    else -> "hosts / domain / EasyList format"
                                },
                            )
                        },
                        isError = invalid || duplicate,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = trimmed.isNotEmpty() && !invalid && !duplicate,
                        onClick = {
                            pref.set(urls + trimmed)
                            showAddDialog = false
                        },
                    ) { Text("Add") }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}
