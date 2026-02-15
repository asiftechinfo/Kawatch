
@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.kawatch

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.example.kawatch.ui.theme.KawatchTheme
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val TARGET_VOICE_COMMAND = "help"
private const val PREFS_NAME = "KawatchPrefs"
private const val PREF_KEY_USER_PROFILE = "userProfile"

data class UserProfile(val name: String, val age: String, val emergencyNumber: String)
data class RecordingItem(val name: String, val path: String)

enum class AppState {
    CHECKING_PERMISSIONS, REQUIRES_PERMISSIONS, REQUIRES_ACCESSIBILITY, REQUIRES_PROFILE, READY, RECORDING_LIBRARY, SOS_COUNTDOWN
}

class MainActivity : ComponentActivity() {

    private val appState = mutableStateOf(AppState.CHECKING_PERMISSIONS)
    private val userProfile = mutableStateOf<UserProfile?>(null)
    private val triggerSos = mutableStateOf(false)
    private val triggerShake = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KawatchTheme(darkTheme = true) {
                if (intent?.action == "ACTION_TRIGGER_SOS_FROM_SHAKE") {
                    Log.d("MainActivity", "SOS triggered from shake intent.")
                    triggerShake.value = true
                    intent.action = null
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigator(appState, userProfile, triggerSos, triggerShake)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (appState.value == AppState.REQUIRES_PERMISSIONS || appState.value == AppState.REQUIRES_ACCESSIBILITY) {
            Log.d("MainActivity", "Re-checking permissions on resume")
            appState.value = AppState.CHECKING_PERMISSIONS
        }
    }
}

@Composable
fun AppNavigator(
    appState: MutableState<AppState>,
    userProfile: MutableState<UserProfile?>,
    triggerSos: MutableState<Boolean>,
    triggerShake: MutableState<Boolean>
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val gson = remember { Gson() }

    if (triggerShake.value) {
        appState.value = AppState.SOS_COUNTDOWN
        triggerShake.value = false
    }

    LaunchedEffect(appState.value) {
        if (appState.value == AppState.CHECKING_PERMISSIONS) {
            val permissionsGranted = getRequiredPermissions().all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            if (!permissionsGranted) {
                appState.value = AppState.REQUIRES_PERMISSIONS
            } else if (!isAccessibilityServiceEnabled(context)) {
                appState.value = AppState.REQUIRES_ACCESSIBILITY
            } else {
                val profileJson = sharedPrefs.getString(PREF_KEY_USER_PROFILE, null)
                if (profileJson == null) {
                    appState.value = AppState.REQUIRES_PROFILE
                } else {
                    // Only load profile if it's not already loaded
                    if (userProfile.value == null) {
                        userProfile.value = gson.fromJson(profileJson, UserProfile::class.java)
                    }
                    appState.value = AppState.READY
                }
            }
        }
    }

    when (appState.value) {
        AppState.CHECKING_PERMISSIONS -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
        }
        AppState.REQUIRES_PERMISSIONS -> PermissionScreen { appState.value = AppState.CHECKING_PERMISSIONS }
        AppState.REQUIRES_ACCESSIBILITY -> AccessibilityPermissionScreen { appState.value = AppState.CHECKING_PERMISSIONS }
        AppState.REQUIRES_PROFILE -> ProfileScreen { profile ->
            userProfile.value = profile
            sharedPrefs.edit { putString(PREF_KEY_USER_PROFILE, gson.toJson(profile)) }
            appState.value = AppState.CHECKING_PERMISSIONS
        }
        AppState.SOS_COUNTDOWN -> SosCountdownScreen(
            onFinished = {
                val profileJson = sharedPrefs.getString(PREF_KEY_USER_PROFILE, null)
                if (profileJson != null) {
                    userProfile.value = gson.fromJson(profileJson, UserProfile::class.java)
                    triggerSos.value = true
                    appState.value = AppState.READY
                } else {
                    appState.value = AppState.REQUIRES_PROFILE
                }
            },
            onCancel = { appState.value = AppState.READY }
        )
        AppState.READY -> userProfile.value?.let {
            ActivationScreen(
                userProfile = it,
                onResetProfile = {
                    sharedPrefs.edit { remove(PREF_KEY_USER_PROFILE) }
                    appState.value = AppState.REQUIRES_PROFILE
                },
                onShowRecordings = { appState.value = AppState.RECORDING_LIBRARY },
                externalTrigger = triggerSos
            )
        } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // This will show if READY state is reached but profile is null (e.g., after cancel from countdown)
            CircularProgressIndicator(color = MaterialTheme.colorScheme.tertiary)
        }
        AppState.RECORDING_LIBRARY -> RecordingsScreen { appState.value = AppState.READY }
    }
}

@Composable
fun ActivationScreen(
    userProfile: UserProfile,
    onResetProfile: () -> Unit,
    onShowRecordings: () -> Unit,
    externalTrigger: MutableState<Boolean>
) {
    val context = LocalContext.current
    var statusMessage by remember { mutableStateOf("Ready: Say \"$TARGET_VOICE_COMMAND\" or shake phone") }
    var isSosActive by remember { mutableStateOf(false) }
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var mmsIntentLaunched by remember { mutableStateOf(false) }

    val onDeactivate = {
        val finalAudioFile = audioFile
        stopRecording(mediaRecorder)
        mediaRecorder = null
        audioFile = null
        isSosActive = false
        statusMessage = "Ready: Say \"$TARGET_VOICE_COMMAND\" or shake phone"

        if (finalAudioFile != null && finalAudioFile.exists() && !mmsIntentLaunched) {
            mmsIntentLaunched = true
            Toast.makeText(context, "SOS deactivated. Recording saved.", Toast.LENGTH_LONG).show()
            val message = "Follow-up audio from the recent emergency alert from ${userProfile.name}."
            sendMmsWithAttachment(context, userProfile.emergencyNumber, message, finalAudioFile)
        } else {
            Toast.makeText(context, "SOS deactivated.", Toast.LENGTH_SHORT).show()
        }
        startListening(context, speechRecognizer)
    }

    val onActivate = onActivate@{
        if (isSosActive) return@onActivate
        Toast.makeText(context, "SOS Activated! Sending alerts...", Toast.LENGTH_LONG).show()
        mmsIntentLaunched = false
        isSosActive = true
        speechRecognizer.stopListening()
        statusMessage = "SOS! Sending location alert..."

        requestCurrentLocation(context) { location: Location? ->
            val locationString = if (location != null) "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}" else "Location not available."
            val ageInfo = if (userProfile.age.isNotBlank()) " Age: ${userProfile.age}." else ""
            val textMessage = "EMERGENCY from ${userProfile.name}!$ageInfo My location: $locationString"
            sendSms(context, userProfile.emergencyNumber, textMessage)
            statusMessage = "Alert sent! Recording audio..."
        }

        val newAudioFile = createAudioFile(context)
        audioFile = newAudioFile
        mediaRecorder = startRecording(context, newAudioFile)
        if (mediaRecorder == null) statusMessage = "Alert sent! Failed to start recording."
    }

    LaunchedEffect(externalTrigger.value) {
        if (externalTrigger.value) {
            onActivate()
            externalTrigger.value = false
        }
    }

    DisposableEffect(context) {
        val listener = createRecognitionListener(onActivate, { isSosActive }, { startListening(context, speechRecognizer) })
        speechRecognizer.setRecognitionListener(listener)
        startListening(context, speechRecognizer)
        onDispose {
            speechRecognizer.destroy()
            stopRecording(mediaRecorder)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KAWATCH", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onShowRecordings, enabled = !isSosActive) { Icon(Icons.AutoMirrored.Filled.List, "Recording Library", tint = if (!isSosActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                    IconButton(onClick = onResetProfile, enabled = !isSosActive) { Icon(Icons.Default.Edit, "Edit Profile", tint = if (!isSosActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("STATUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.height(8.dp))
                    Text(statusMessage, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, color = if (isSosActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
            if (isSosActive) SosActiveIndicator(onDeactivate) else SosActivationButton(onActivate)
            val helpText = "Say \"$TARGET_VOICE_COMMAND\", shake the phone, or press the button to activate."
            Text(helpText, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 16.dp, start = 16.dp, end = 16.dp))
        }
    }
}

@Composable
fun SosCountdownScreen(onFinished: () -> Unit, onCancel: () -> Unit) {
    var remainingTime by remember { mutableIntStateOf(5) }
    var triggerFinish by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (remainingTime > 0) {
            delay(1000)
            remainingTime--
        }
        triggerFinish = true
    }

    LaunchedEffect(triggerFinish) {
        if (triggerFinish) {
            onFinished()
        }
    }

    val transition = rememberInfiniteTransition(label = "countdown_transition")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale_animation"
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.error) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("SOS IN", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onError.copy(alpha = 0.8f))
            Spacer(Modifier.height(16.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { remainingTime / 5f },
                    modifier = Modifier.size(200.dp),
                    color = MaterialTheme.colorScheme.onError,
                    strokeWidth = 16.dp,
                    trackColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    strokeCap = StrokeCap.Round
                )
                Text(
                    text = "$remainingTime",
                    fontSize = 80.sp,
                    color = MaterialTheme.colorScheme.onError,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.scale(scale)
                )
            }
            Spacer(Modifier.height(40.dp))
            Text("SHAKE DETECTED", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onCancel, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("CANCEL SOS", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
fun PermissionScreen(onPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val permissionsToRequest = getRequiredPermissions()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            onPermissionsGranted()
        } else {
            Toast.makeText(context, "All permissions are required to use this app.", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(Unit) { launcher.launch(permissionsToRequest) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Permissions Required") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Shield, "Permissions Icon", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("Permissions Needed", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Kawatch requires Audio, Location, and SMS permissions to protect you. Your data is never shared.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(32.dp))
            Button(onClick = { launcher.launch(permissionsToRequest) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("GRANT PERMISSIONS", color = MaterialTheme.colorScheme.onTertiary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AccessibilityPermissionScreen(onSettingsClicked: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("Enable Service") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Settings, "Settings Icon", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(24.dp))
            Text("Enable Shake Trigger", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(
                "To trigger an SOS by shaking your phone when the app is in the background, you must enable the Kawatch accessibility service.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                    onSettingsClicked()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("OPEN SETTINGS", color = MaterialTheme.colorScheme.onTertiary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun ProfileScreen(onProfileSaved: (UserProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf(false) }
    var numberError by remember { mutableStateOf(false) }
    val textFieldColors = TextFieldDefaults.colors(
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        cursorColor = MaterialTheme.colorScheme.tertiary,
        focusedIndicatorColor = MaterialTheme.colorScheme.tertiary,
        unfocusedIndicatorColor = Color.Transparent,
        focusedLabelColor = MaterialTheme.colorScheme.tertiary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        errorIndicatorColor = MaterialTheme.colorScheme.error,
        errorLabelColor = MaterialTheme.colorScheme.error
    )
    Scaffold(
        topBar = { TopAppBar(title = { Text("Create Your Profile") }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Person, "Profile Icon", tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("This info is sent to your emergency contact.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(value = name, onValueChange = { name = it; nameError = false }, label = { Text("Your Name") }, isError = nameError, singleLine = true, modifier = Modifier.fillMaxWidth(), colors = textFieldColors)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = age, onValueChange = { age = it }, label = { Text("Your Age (Optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), colors = textFieldColors)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = number, onValueChange = { number = it; numberError = false }, label = { Text("Emergency Phone Number") }, isError = numberError, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, modifier = Modifier.fillMaxWidth(), colors = textFieldColors)
            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    nameError = name.isBlank()
                    numberError = number.isBlank()
                    if (!nameError && !numberError) {
                        onProfileSaved(UserProfile(name, age, number))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("SAVE PROFILE", color = MaterialTheme.colorScheme.onTertiary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun RecordingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var recordings by remember { mutableStateOf(getSavedRecordings(context)) }
    var currentlyPlaying by remember { mutableStateOf<String?>(null) }
    val mediaPlayer = remember { MediaPlayer() }
    fun playRecording(path: String) {
        try {
            if (currentlyPlaying == path) {
                mediaPlayer.stop(); mediaPlayer.reset(); currentlyPlaying = null
            } else {
                mediaPlayer.reset(); mediaPlayer.setDataSource(path); mediaPlayer.prepare(); mediaPlayer.start(); currentlyPlaying = path
                mediaPlayer.setOnCompletionListener { currentlyPlaying = null }
            }
        } catch (e: Exception) {
            Log.e("MediaPlayer", "Error playing recording", e); currentlyPlaying = null
        }
    }
    fun deleteRecording(item: RecordingItem) {
        val file = File(item.path)
        if (file.exists()) {
            if (currentlyPlaying == item.path) { mediaPlayer.stop(); mediaPlayer.reset(); currentlyPlaying = null }
            file.delete()
            recordings = getSavedRecordings(context)
            Toast.makeText(context, "${item.name} deleted.", Toast.LENGTH_SHORT).show()
        }
    }
    fun saveRecordingToDownloads(item: RecordingItem) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(context, "Saving to Downloads requires Android 10+.", Toast.LENGTH_LONG).show(); return
        }
        val sourceFile = File(item.path)
        if (!sourceFile.exists()) { Toast.makeText(context, "File not found.", Toast.LENGTH_SHORT).show(); return }
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name); put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp3"); put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) { Toast.makeText(context, "Failed to create file in Downloads.", Toast.LENGTH_SHORT).show(); return }
        try {
            resolver.openOutputStream(uri).use { outputStream -> sourceFile.inputStream().use { it.copyTo(outputStream!!) } }
            Toast.makeText(context, "Saved to Downloads folder.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("SaveRecording", "File copy failed", e); Toast.makeText(context, "Error saving file.", Toast.LENGTH_SHORT).show()
        }
    }
    DisposableEffect(Unit) { onDispose { mediaPlayer.release() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Emergency Recordings") }, navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary, titleContentColor = MaterialTheme.colorScheme.onPrimary, navigationIconContentColor = MaterialTheme.colorScheme.onPrimary)) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (recordings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("No recordings found.", color = MaterialTheme.colorScheme.onSurface) }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(recordings) { recording ->
                    val isPlaying = currentlyPlaying == recording.path
                    ListItem(
                        headlineContent = { Text(recording.name, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { playRecording(recording.path) },
                        leadingContent = { Icon(if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Play/Pause", tint = MaterialTheme.colorScheme.tertiary) },
                        trailingContent = {
                            Row {
                                IconButton({ saveRecordingToDownloads(recording) }) { Icon(Icons.Default.Download, "Download", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)) }
                                IconButton({ deleteRecording(recording) }) { Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                            }
                        },
                        colors = ListItemDefaults.colors(Color.Transparent)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surface)
                }
            }
        }
    }
}

@Composable
fun SosActiveIndicator(onDeactivate: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition("sos_indicator")
    val colorAlpha by infiniteTransition.animateFloat(0.5f, 1.0f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), "sos_alpha")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("SOS IN PROGRESS", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error.copy(alpha = colorAlpha), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Button(onDeactivate, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)), modifier = Modifier.fillMaxWidth(0.8f).height(60.dp)) {
            Text("DEACTIVATE & SEND AUDIO", color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SosActivationButton(onActivate: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition("pulse")
    val pulseAlpha by infiniteTransition.animateFloat(0.3f, 0.7f, infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Reverse), "pulse_alpha")
    val animatedCircleColor = MaterialTheme.colorScheme.error.copy(alpha = pulseAlpha)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(220.dp)) { drawCircle(color = animatedCircleColor, style = Stroke(width = (size.width * 0.1f * (1 - (pulseAlpha - 0.3f) / 0.4f)))) }
        Button(onActivate, modifier = Modifier.size(180.dp), shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), elevation = ButtonDefaults.buttonElevation(8.dp, 4.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Warning, "SOS Icon", modifier = Modifier.size(60.dp), tint = MaterialTheme.colorScheme.onError)
                Spacer(Modifier.height(8.dp))
                Text("SOS", color = MaterialTheme.colorScheme.onError, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun getRequiredPermissions(): Array<String> {
    val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS)
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE); permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return permissions.toTypedArray()
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val serviceId = "${context.packageName}/${SosAccessibilityService::class.java.name}"
    try {
        val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (settingValue != null) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(settingValue)
            while (splitter.hasNext()) {
                if (splitter.next().equals(serviceId, ignoreCase = true)) {
                    return true
                }
            }
        }
    } catch (e: Exception) {
        Log.e("AccessibilityCheck", "Failed to check accessibility service", e)
    }
    return false
}

private fun getRecordingsDir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }
private fun createAudioFile(context: Context): File = File(getRecordingsDir(context), "SOS_RECORDING_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp3")
private fun startRecording(context: Context, file: File): MediaRecorder? {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
    return try {
        recorder.apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setOutputFile(file.absolutePath); prepare(); start() }
    } catch (e: Exception) {
        Log.e("MediaRecorder", "Prepare failed", e); Toast.makeText(context, "Failed to start recording.", Toast.LENGTH_SHORT).show(); null
    }
}
private fun stopRecording(mediaRecorder: MediaRecorder?) { mediaRecorder?.let { try { it.stop(); it.release() } catch (e: Exception) { Log.e("MediaRecorder", "Stop failed", e) } } }
private fun getSavedRecordings(context: Context): List<RecordingItem> = getRecordingsDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".mp3") }?.map { RecordingItem(it.name, it.absolutePath) }?.sortedByDescending { it.name } ?: emptyList()
private fun sendSms(context: Context, phoneNumber: String, message: String) {
    try {
        context.getSystemService(SmsManager::class.java).sendTextMessage(phoneNumber, null, message, null, null)
        Toast.makeText(context, "Emergency SMS sent!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "SMS failed. Check permissions.", Toast.LENGTH_LONG).show(); Log.e("SmsError", "SMS send failed", e)
    }
}
private fun sendMmsWithAttachment(context: Context, phoneNumber: String, text: String, attachment: File) {
    try {
        val attachmentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", attachment)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp3"; putExtra("address", phoneNumber); putExtra("sms_body", text); putExtra(Intent.EXTRA_STREAM, attachmentUri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Telephony.Sms.getDefaultSmsPackage(context)?.let { setPackage(it) }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) else Toast.makeText(context, "No app found to send MMS.", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Log.e("MmsError", "MMS intent failed", e); Toast.makeText(context, "Could not create MMS. Send recording from library.", Toast.LENGTH_LONG).show()
    }
}
private fun createRecognitionListener(onActivation: () -> Unit, isSosActive: () -> Boolean, startListening: () -> Unit): RecognitionListener {
    return object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { if (!isSosActive()) startListening() }
        override fun onResults(results: Bundle?) {
            if (isSosActive()) return
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.any { it.contains(TARGET_VOICE_COMMAND, ignoreCase = true) }) {
                onActivation()
            }
            startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
private fun startListening(context: Context, speechRecognizer: SpeechRecognizer) {
    if (SpeechRecognizer.isRecognitionAvailable(context)) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer.startListening(intent)
    }
}
private fun requestCurrentLocation(context: Context, onLocationResult: (Location?) -> Unit) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).addOnSuccessListener { onLocationResult(it) }.addOnFailureListener { onLocationResult(null) }
    } else {
        onLocationResult(null)
    }
}
