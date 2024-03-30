package me.rhunk.snapenhance.ui.manager.pages.social

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.*
import me.rhunk.snapenhance.bridge.snapclient.MessagingBridge
import me.rhunk.snapenhance.bridge.snapclient.SessionStartListener
import me.rhunk.snapenhance.bridge.snapclient.types.Message
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.ReceiversConfig
import me.rhunk.snapenhance.common.data.ContentType
import me.rhunk.snapenhance.common.data.SocialScope
import me.rhunk.snapenhance.common.util.protobuf.ProtoReader
import me.rhunk.snapenhance.common.util.snap.SnapWidgetBroadcastReceiverHelper
import me.rhunk.snapenhance.messaging.MessagingConstraints
import me.rhunk.snapenhance.messaging.MessagingTask
import me.rhunk.snapenhance.messaging.MessagingTaskConstraint
import me.rhunk.snapenhance.messaging.MessagingTaskType
import me.rhunk.snapenhance.ui.manager.Routes
import me.rhunk.snapenhance.ui.util.Dialog

class MessagingPreview: Routes.Route() {
    private lateinit var coroutineScope: CoroutineScope
    private lateinit var messagingBridge: MessagingBridge
    private lateinit var previewScrollState: LazyListState

    private val myUserId by lazy { messagingBridge.myUserId }
    private val contentTypeTranslation by lazy { context.translation.getCategory("content_type") }

    private var messages = mutableStateListOf<Message>()
    private var conversationId by mutableStateOf<String?>(null)
    private val selectedMessages = mutableStateListOf<Long>() // client message id

    private fun toggleSelectedMessage(messageId: Long) {
        if (selectedMessages.contains(messageId)) selectedMessages.remove(messageId)
        else selectedMessages.add(messageId)
    }

    @Composable
    private fun ActionButton(
        text: String,
        icon: ImageVector,
        onClick: () -> Unit,
    ) {
        DropdownMenuItem(
            onClick = onClick,
            text = {
                Row(
                    modifier = Modifier.padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null
                    )
                    Text(text = text)
                }
            }
        )
    }

    @Composable
    private fun ConstraintsSelectionDialog(
        onChoose: (Array<ContentType>) -> Unit,
        onDismiss: () -> Unit
    ) {
        val selectedTypes = remember { mutableStateListOf<ContentType>() }
        var selectAllState by remember { mutableStateOf(false) }
        val availableTypes = remember { arrayOf(
            ContentType.CHAT,
            ContentType.NOTE,
            ContentType.SNAP,
            ContentType.STICKER,
            ContentType.EXTERNAL_MEDIA
        ) }

        fun toggleContentType(contentType: ContentType) {
            if (selectAllState) return
            if (selectedTypes.contains(contentType)) {
                selectedTypes.remove(contentType)
            } else {
                selectedTypes.add(contentType)
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text("Choose content types to process")
                Spacer(modifier = Modifier.height(5.dp))
                availableTypes.forEach { contentType ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(2.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { toggleContentType(contentType) })
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedTypes.contains(contentType),
                            enabled = !selectAllState,
                            onCheckedChange = { toggleContentType(contentType) }
                        )
                        Text(text = contentType.toString())
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(checked = selectAllState, onCheckedChange = {
                        selectAllState = it
                    })
                    Text(text = "Select all")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Button(onClick = { onDismiss() }) {
                        Text("Cancel")
                    }
                    Button(onClick = {
                        onChoose(if (selectAllState) ContentType.entries.toTypedArray()
                         else selectedTypes.toTypedArray())
                    }) {
                        Text("Continue")
                    }
                }
            }
        }
    }

    override val topBarActions: @Composable (RowScope.() -> Unit) = {
        var taskSelectionDropdown by remember { mutableStateOf(false) }
        var selectConstraintsDialog by remember { mutableStateOf(false) }
        var activeTask by remember { mutableStateOf(null as MessagingTask?) }
        var activeJob by remember { mutableStateOf(null as Job?) }
        val processMessageCount = remember { mutableIntStateOf(0) }

        fun runCurrentTask() {
            activeJob = coroutineScope.launch(Dispatchers.IO) {
                activeTask?.run()
                withContext(Dispatchers.Main) {
                    activeTask = null
                    activeJob = null
                }
            }.also { job ->
                job.invokeOnCompletion {
                    if (it != null) {
                        context.log.verbose("Failed to process messages: ${it.message}")
                        return@invokeOnCompletion
                    }
                    context.longToast("Processed ${processMessageCount.intValue} messages")
                }
            }
        }

        fun launchMessagingTask(taskType: MessagingTaskType, constraints: List<MessagingTaskConstraint> = listOf(), onSuccess: (Message) -> Unit = {}) {
            taskSelectionDropdown = false
            processMessageCount.intValue = 0
            activeTask = MessagingTask(
                messagingBridge, conversationId!!, taskType, constraints,
                overrideClientMessageIds = selectedMessages.takeIf { it.isNotEmpty() }?.toList(),
                processedMessageCount = processMessageCount,
                onSuccess = onSuccess,
                onFailure = { message, reason ->
                    context.log.verbose("Failed to process message ${message.clientMessageId}: $reason")
                }
            )
            selectedMessages.clear()
        }

        if (selectConstraintsDialog && activeTask != null) {
            Dialog(onDismissRequest = {
                selectConstraintsDialog = false
                activeTask = null
            }) {
                ConstraintsSelectionDialog(
                    onChoose = { contentTypes ->
                        launchMessagingTask(
                            taskType = activeTask!!.taskType,
                            constraints = activeTask!!.constraints + MessagingConstraints.CONTENT_TYPE(contentTypes),
                            onSuccess = activeTask!!.onSuccess
                        )
                        runCurrentTask()
                        selectConstraintsDialog = false
                    },
                    onDismiss = {
                        selectConstraintsDialog = false
                        activeTask = null
                    }
                )
            }
        }

        if (activeJob != null) {
            Dialog(onDismissRequest = {
                activeJob?.cancel()
                activeJob = null
                activeTask = null
            }) {
                Column(modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(15.dp)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(20.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp))
                {
                    Text("Processed ${processMessageCount.intValue} messages")
                    if (activeTask?.hasFixedGoal() == true) {
                        LinearProgressIndicator(
                            progress = { processMessageCount.intValue.toFloat() / selectedMessages.size.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(5.dp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding()
                                .size(30.dp),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        IconButton(onClick = { taskSelectionDropdown = !taskSelectionDropdown }) {
            Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
        }

        if (selectedMessages.isNotEmpty()) {
            IconButton(onClick = { selectedMessages.clear() }) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
            }
        }

        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                surface = MaterialTheme.colorScheme.inverseSurface,
                onSurface = MaterialTheme.colorScheme.inverseOnSurface
            ),
            shapes = MaterialTheme.shapes.copy(medium = RoundedCornerShape(50.dp))
        ) {
            DropdownMenu(
                expanded = taskSelectionDropdown, onDismissRequest = { taskSelectionDropdown = false }
            ) {
                val hasSelection = selectedMessages.isNotEmpty()
                ActionButton(text = if (hasSelection) "Save selection" else "Save all", icon = Icons.Rounded.BookmarkAdded) {
                    launchMessagingTask(MessagingTaskType.SAVE)
                    if (hasSelection) runCurrentTask()
                    else selectConstraintsDialog = true
                }
                ActionButton(text = if (hasSelection) "Unsave selection" else "Unsave all", icon = Icons.Rounded.BookmarkBorder) {
                    launchMessagingTask(MessagingTaskType.UNSAVE)
                    if (hasSelection) runCurrentTask()
                    else selectConstraintsDialog = true
                }
                ActionButton(text = if (hasSelection) "Mark selected Snap as seen" else "Mark all Snaps as seen", icon = Icons.Rounded.RemoveRedEye) {
                    launchMessagingTask(MessagingTaskType.READ, listOf(
                        MessagingConstraints.NO_USER_ID(myUserId),
                        MessagingConstraints.CONTENT_TYPE(arrayOf(ContentType.SNAP))
                    ))
                    runCurrentTask()
                }
                ActionButton(text = if (hasSelection) "Delete selected" else "Delete all", icon = Icons.Rounded.DeleteForever) {
                    launchMessagingTask(MessagingTaskType.DELETE, listOf(MessagingConstraints.USER_ID(myUserId))) { message ->
                        coroutineScope.launch {
                            message.contentType = ContentType.STATUS.id
                        }
                    }
                    if (hasSelection) runCurrentTask()
                    else selectConstraintsDialog = true
                }
            }
        }
    }

    @Composable
    private fun ConversationPreview(
        messages: List<Message>,
        fetchNewMessages: () -> Unit
    ) {
        DisposableEffect(Unit) {
            onDispose {
                selectedMessages.clear()
            }
        }

        LazyColumn(
            reverseLayout = true,
            modifier = Modifier
                .fillMaxWidth(),
            state = previewScrollState,
        ) {
            items(messages, key = { it.serverMessageId }) {message ->
                val messageReader = remember(message.contentType) { ProtoReader(message.content) }
                val contentType = ContentType.fromMessageContainer(messageReader)

                Card(
                    modifier = Modifier
                        .padding(5.dp)
                        .pointerInput(Unit) {
                            if (contentType == ContentType.STATUS) return@pointerInput
                            detectTapGestures(
                                onLongPress = {
                                    toggleSelectedMessage(message.clientMessageId)
                                },
                                onTap = {
                                    if (selectedMessages.isNotEmpty()) {
                                        toggleSelectedMessage(message.clientMessageId)
                                    }
                                }
                            )
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedMessages.contains(message.clientMessageId)) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    val contentMessage = remember(message.contentType) { "[${contentType?.let { contentTypeTranslation.getOrNull(it.name) ?: it.name } }] ${messageReader.getString(2, 1) ?: "" }" }
                    Row(
                        modifier = Modifier
                            .padding(5.dp)
                    ) {
                        Text(contentMessage)
                    }
                }
            }
            item {
                if (messages.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(40.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text("No messages")
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))

                LaunchedEffect(Unit) {
                    if (messages.isNotEmpty()) {
                        fetchNewMessages()
                    }
                }
            }
        }
    }


    @Composable
    private fun LoadingRow() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding()
                    .size(30.dp),
                strokeWidth = 3.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    override val content: @Composable (NavBackStackEntry) -> Unit = { navBackStackEntry ->
        val scope = remember { SocialScope.getByName(navBackStackEntry.arguments?.getString("scope")!!) }
        val id = remember { navBackStackEntry.arguments?.getString("id")!! }

        previewScrollState = rememberLazyListState()
        coroutineScope = rememberCoroutineScope()

        var lastMessageId by remember { mutableLongStateOf(Long.MAX_VALUE) }
        var isBridgeConnected by remember { mutableStateOf(false) }
        var hasBridgeError by remember { mutableStateOf(false) }

        fun fetchNewMessages() {
            coroutineScope.launch(Dispatchers.IO) cs@{
                runCatching {
                    val queriedMessages = messagingBridge.fetchConversationWithMessagesPaginated(
                        conversationId!!,
                        20,
                        lastMessageId
                    )?.reversed()

                    if (queriedMessages == null) {
                        context.shortToast("Failed to fetch messages")
                        return@cs
                    }

                    withContext(Dispatchers.Main) {
                        messages.addAll(queriedMessages)
                        lastMessageId = queriedMessages.lastOrNull()?.clientMessageId ?: lastMessageId
                    }
                }.onFailure {
                    context.log.error("Failed to fetch messages", it)
                    context.shortToast("Failed to fetch messages: ${it.message}")
                }
            }
        }

        fun onMessagingBridgeReady(scope: SocialScope, scopeId: String) {
            context.log.verbose("onMessagingBridgeReady: $scope $scopeId")

            runCatching {
                messagingBridge = context.bridgeService!!.messagingBridge!!
                conversationId = if (scope == SocialScope.FRIEND) messagingBridge.getOneToOneConversationId(scopeId) else scopeId
                if (conversationId == null) {
                    context.longToast("Failed to fetch conversation id")
                    return
                }
                if (runCatching { !messagingBridge.isSessionStarted }.getOrDefault(true)) {
                    context.androidContext.packageManager.getLaunchIntentForPackage(
                        Constants.SNAPCHAT_PACKAGE_NAME
                    )?.let {
                        val mainIntent = Intent.makeRestartActivityTask(it.component).apply {
                            putExtra(ReceiversConfig.MESSAGING_PREVIEW_EXTRA, true)
                        }
                        context.androidContext.startActivity(mainIntent)
                    }
                    messagingBridge.registerSessionStartListener(object: SessionStartListener.Stub() {
                        override fun onConnected() {
                            fetchNewMessages()
                        }
                    })
                    return
                }
                fetchNewMessages()
            }.onFailure {
                context.longToast("Failed to initialize messaging bridge")
                context.log.error("Failed to initialize messaging bridge", it)
            }
        }

        LaunchedEffect(Unit) {
            messages.clear()
            conversationId = null

            isBridgeConnected = context.hasMessagingBridge()
            if (isBridgeConnected) {
                onMessagingBridgeReady(scope, id)
            } else {
                SnapWidgetBroadcastReceiverHelper.create("wakeup") {}.also {
                    context.androidContext.sendBroadcast(it)
                }
                coroutineScope.launch(Dispatchers.IO) {
                    withTimeout(10000) {
                        while (!context.hasMessagingBridge()) {
                            delay(100)
                        }
                        isBridgeConnected = true
                        onMessagingBridgeReady(scope, id)
                    }
                }.invokeOnCompletion {
                    if (it != null) {
                        hasBridgeError = true
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (hasBridgeError) {
                Text("Failed to connect to Snapchat through bridge service")
            }

            if (!isBridgeConnected && !hasBridgeError) {
                LoadingRow()
            }

            if (isBridgeConnected && !hasBridgeError) {
                ConversationPreview(messages, ::fetchNewMessages)
            }
        }
    }
}