package io.github.fate_grand_automata.ui.pref_support

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fate_grand_automata.R
import io.github.fate_grand_automata.supportcatalog.CatalogServant
import io.github.fate_grand_automata.supportcatalog.ServantCatalog
import io.github.fate_grand_automata.supportcatalog.SupportPortraitImporter
import io.github.fate_grand_automata.supportcatalog.SupportServantStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@Composable
internal fun ImportCatalogServantDialog(onDismiss: () -> Unit, onImported: (String) -> Unit) {
    val store = SupportServantStore.inApp(LocalContext.current.filesDir)
    val region = SupportServantStore.region(LocalConfiguration.current.locales[0])
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<CatalogServant>?>(null) }
    var selected by remember { mutableStateOf<CatalogServant?>(null) }
    var loading by remember { mutableStateOf(true) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var attempts by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempts) {
        loading = true
        error = false
        try {
            catalog = withContext(Dispatchers.IO) { ServantCatalog.fetch() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.w(e, "Servant catalog loading failed")
            error = true
        } finally {
            loading = false
        }
    }

    val matches = remember(catalog, query) {
        catalog.orEmpty().filter { it.matches(query.trim()) }
    }
    AlertDialog(
        onDismissRequest = { if (!importing) onDismiss() },
        title = { Text(stringResource(R.string.support_catalog_import)) },
        text = {
            Column {
                if (loading || importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) Text(stringResource(R.string.support_catalog_error))
                if (catalog != null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it; selected = null },
                        label = { Text(stringResource(R.string.support_catalog_search)) },
                        enabled = !importing,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (matches.isEmpty()) Text(stringResource(R.string.support_catalog_empty))
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(matches, key = { it.id }) { servant ->
                            TextButton(
                                enabled = !importing,
                                onClick = { selected = servant },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val marker = if (selected?.id == servant.id) "✓ " else ""
                                Text("$marker${servant.label(region)} · ${servant.className} · #${servant.collectionNo}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (error && catalog == null) {
                TextButton(onClick = { attempts++ }, enabled = !loading) {
                    Text(stringResource(R.string.support_catalog_retry))
                }
            } else {
                TextButton(
                    enabled = !importing && selected != null,
                    onClick = {
                        val servant = selected ?: return@TextButton
                        importing = true
                        error = false
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { SupportPortraitImporter.import(servant, store) }
                                onImported(servant.key)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Timber.w(e, "Support servant portrait import failed")
                                error = true
                            } finally {
                                importing = false
                            }
                        }
                    }
                ) { Text(stringResource(R.string.support_catalog_add)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
