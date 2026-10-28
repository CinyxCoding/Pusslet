package se.pusslet.feature.calendar.attachments

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import se.pusslet.core.model.AttachmentOwner
import se.pusslet.core.model.LocalAttachment
import se.pusslet.data.repository.AttachmentRepository
import se.pusslet.ui.components.SectionCard
import se.pusslet.ui.platform.LocalPlatformActions
import se.pusslet.ui.resources.Res
import se.pusslet.ui.resources.action_cancel
import se.pusslet.ui.resources.action_delete
import se.pusslet.ui.resources.attachment_open
import se.pusslet.ui.resources.attachment_remove
import se.pusslet.ui.resources.attachment_remove_confirm
import se.pusslet.ui.resources.attachments_only_here
import se.pusslet.ui.resources.attachments_title
import se.pusslet.ui.theme.Spacing

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentsViewModel(private val repo: AttachmentRepository) : ViewModel() {
    private val target = MutableStateFlow<Pair<AttachmentOwner, String>?>(null)

    val attachments: StateFlow<List<LocalAttachment>> = target.filterNotNull()
        .flatMapLatest { (owner, id) -> repo.forOwner(owner, id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    fun load(owner: AttachmentOwner, ownerId: String) {
        target.value = owner to ownerId
    }

    fun remove(attachment: LocalAttachment) {
        viewModelScope.launch { repo.remove(attachment) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** The pictures of an event or note; nothing is shown when there are none. */
@Composable
fun AttachmentsRoute(owner: AttachmentOwner, ownerId: String) {
    val viewModel: AttachmentsViewModel = koinViewModel(key = "attachments:$owner:$ownerId")
    LaunchedEffect(owner, ownerId) { viewModel.load(owner, ownerId) }
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    if (attachments.isNotEmpty()) AttachmentStrip(attachments, onRemove = viewModel::remove)
}

@Composable
fun AttachmentStrip(attachments: List<LocalAttachment>, onRemove: (LocalAttachment) -> Unit) {
    SectionCard(stringResource(Res.string.attachments_title)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            attachments.forEach { AttachmentThumbnail(it, onRemove) }
        }
        Text(
            stringResource(Res.string.attachments_only_here),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AttachmentThumbnail(attachment: LocalAttachment, onRemove: (LocalAttachment) -> Unit) {
    val platform = LocalPlatformActions.current
    var confirm by remember { mutableStateOf(false) }
    val openLabel = stringResource(Res.string.attachment_open)
    Box(
        Modifier
            .size(THUMB.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClickLabel = openLabel) { platform.openImage(attachment.fileName) },
    ) {
        AttachmentImage(attachment.fileName, thumbnail = true, contentDescription = openLabel, modifier = Modifier.fillMaxSize())
        IconButton(
            onClick = { confirm = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.xs).size(REMOVE.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = SCRIM), CircleShape),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.attachment_remove))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(Res.string.attachment_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onRemove(attachment)
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(Res.string.action_cancel)) } },
        )
    }
}

/** Loads a picture from the private folder through the platform (decoding happens off the main thread there). */
@Composable
fun AttachmentImage(fileName: String, thumbnail: Boolean, contentDescription: String?, modifier: Modifier = Modifier) {
    val platform = LocalPlatformActions.current
    var image by remember(fileName, thumbnail) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(fileName, thumbnail) { platform.loadImage(fileName, thumbnail) { image = it } }
    image?.let { Image(it, contentDescription = contentDescription, contentScale = ContentScale.Crop, modifier = modifier) }
}

private const val THUMB = 96
private const val REMOVE = 32
private const val SCRIM = 0.8f
