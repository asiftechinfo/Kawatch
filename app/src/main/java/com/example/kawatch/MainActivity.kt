@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.kawatch

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.provider.Telephony
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.telephony.SubscriptionManager
import android.telephony.SmsManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import android.content.Context

private const val TARGET_VOICE_COMMAND = "help"
private const val PREFS_NAME = "KawatchPrefs"
private const val PREF_KEY_USER_PROFILE = "userProfile"

data class UserProfile(val name: String, val age: String, val emergencyNumber: String)
data class RecordingItem(val name: String, val path: String, val formattedDate: String)

enum class AppState {
    CHECKING_PERMISSIONS, REQUIRES_PERMISSIONS, REQUIRES_ACCESSIBILITY, REQUIRES_PROFILE, READY, RECORDING_LIBRARY, SOS_COUNTDOWN
}

class MainActivity : ComponentActivity() {

    private val appState = mutableStateOf(AppState.CHECKING_PERMISSIONS)
    private val userProfile = mutableStateOf<UserProfile?>(null)
    private val triggerSos = mutableStateOf(false)
    private val triggerExternal = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            KawatchTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavigator(appState, userProfile, triggerSos, triggerExternal)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == "ACTION_TRIGGER_SOS_FROM_KEYS") {
            Log.d("Kawatch", "SOS triggered from keys")
            triggerExternal.value = true
            intent.action = null
        }
    }

    override fun onResume() {
        super.onResume()
        if (appState.value == AppState.REQUIRES_PERMISSIONS || appState.value == AppState.REQUIRES_ACCESSIBILITY) {
            appState.value = AppState.CHECKING_PERMISSIONS
        }
    }
}

@Composable
fun AppNavigator(
    appState: MutableState<AppState>,
    userProfile: MutableState<UserProfile?>,
    triggerSos: MutableState<Boolean>,
    triggerExternal: MutableState<Boolean>
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val gson = remember { Gson() }
    val activity = (LocalContext.current as? Activity)

    if (triggerExternal.value) {
        appState.value = AppState.SOS_COUNTDOWN
        triggerExternal.value = false
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
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 4.dp)
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
            onCancel = { activity?.finish() }
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
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf("READY") }
    var isSosActive by remember { mutableStateOf(false) }
    val speechRecognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var mmsIntentLaunched by remember { mutableStateOf(false) }

    val onDeactivate = {
        Log.d("Kawatch", "Deactivating SOS")
        val finalAudioFile = audioFile
        stopRecording(mediaRecorder)
        mediaRecorder = null
        isSosActive = false
        statusMessage = "READY"

        if (finalAudioFile != null && finalAudioFile.exists() && !mmsIntentLaunched) {
            mmsIntentLaunched = true
            Toast.makeText(context, "Preparing recording...", Toast.LENGTH_SHORT).show()
            val message = "Emergency recording from ${userProfile.name}."

            scope.launch {
                delay(1000)
                sendMmsWithAttachment(context, userProfile.emergencyNumber, message, finalAudioFile)
            }
        } else {
            Toast.makeText(context, "SOS stopped.", Toast.LENGTH_SHORT).show()
        }
        startListening(context, speechRecognizer)
    }

    val onActivate = onActivate@{
        if (isSosActive) return@onActivate
        Log.d("Kawatch", "Activating SOS")
        mmsIntentLaunched = false
        isSosActive = true
        speechRecognizer.stopListening()
        statusMessage = "FETCHING LOCATION..."

        // 1. Request Location FIRST
        requestCurrentLocation(context) { location: Location? ->

            // 2. Format the location and send the SMS
            val locationString = if (location != null) {
                "https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "Location not available."
            }

            val ageInfo = if (userProfile.age.isNotBlank()) " Age: ${userProfile.age}." else ""
            val textMessage = "EMERGENCY! ${userProfile.name} needs help!$ageInfo My location is: $locationString"

            sendSms(context, userProfile.emergencyNumber, textMessage)
            statusMessage = "RECORDING..."

            // 3. Start Recording AFTER the SMS is sent
            val newAudioFile = createAudioFile(context)
            audioFile = newAudioFile
            mediaRecorder = startRecording(context, newAudioFile)

            if (mediaRecorder != null) {
                // 4. Show the requested Toast
                Toast.makeText(context, "Recording started. Stop recording to send MMS.", Toast.LENGTH_LONG).show()
            } else {
                statusMessage = "RECORDING FAILED"
            }
        }
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
            CenterAlignedTopAppBar(
                title = { Text("KAWATCH", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)) },
                actions = {
                    IconButton(onClick = onShowRecordings, enabled = !isSosActive) { Icon(Icons.AutoMirrored.Filled.List, "Recordings", tint = MaterialTheme.colorScheme.onSurface) }
                    IconButton(onClick = onResetProfile, enabled = !isSosActive) { Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurface) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = if (isSosActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                if (!isSosActive) {
                    Text(
                        text = "Say \"$TARGET_VOICE_COMMAND\" or press both volume keys",
                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            if (isSosActive) SosActiveIndicator(onDeactivate) else SosActivationButton(onActivate)

            // Bottom Profile Summary
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(userProfile.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                        Text("Emergency: ${userProfile.emergencyNumber}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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

    val transition = rememberInfiniteTransition(label = "countdown")
    val scale by transition.animateFloat(1f, 1.2f, infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")

    Scaffold(containerColor = MaterialTheme.colorScheme.error) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("SOS IN", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onError.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(32.dp))
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { remainingTime / 5f }, modifier = Modifier.size(240.dp), color = MaterialTheme.colorScheme.onError, strokeWidth = 12.dp, trackColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.2f), strokeCap = StrokeCap.Round)
                Text(text = "$remainingTime", fontSize = 100.sp, color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Black, modifier = Modifier.scale(scale))
            }
            Spacer(Modifier.height(64.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text("CANCEL", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun PermissionScreen(onPermissionsGranted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) onPermissionsGranted()
        else Toast.makeText(context, "All permissions are required.", Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) { launcher.launch(getRequiredPermissions()) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(120.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(60.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text("Permissions Needed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("Kawatch needs Audio, Location, and SMS permissions to protect you.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(48.dp))
            Button(onClick = { launcher.launch(getRequiredPermissions()) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Grant Permissions", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun AccessibilityPermissionScreen(onServiceEnabled: () -> Unit) {
    val context = LocalContext.current
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f), modifier = Modifier.size(120.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Accessibility, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(60.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
            Text("Enable Accessibility", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text("To detect volume key presses even when the app is closed, Kawatch needs Accessibility Service enabled.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(48.dp))
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Open Settings", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onServiceEnabled, modifier = Modifier.padding(top = 8.dp)) { Text("Check again") }
        }
    }
}

@Composable
fun ProfileScreen(onSave: (UserProfile) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    var emergencyNumber by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("PROFILE", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Emergency Information", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full Name") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Person, null) }
            )
            OutlinedTextField(
                value = age,
                onValueChange = { age = it },
                label = { Text("Age (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                leadingIcon = { Icon(Icons.Default.DateRange, null) }
            )
            OutlinedTextField(
                value = emergencyNumber,
                onValueChange = { emergencyNumber = it },
                label = { Text("Emergency Contact Number") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                leadingIcon = { Icon(Icons.Default.Phone, null) }
            )
            
            Spacer(Modifier.weight(1f))
            
            Button(
                onClick = { 
                    if (name.isNotBlank() && emergencyNumber.isNotBlank()) onSave(UserProfile(name.trim(), age.trim(), emergencyNumber.trim())) 
                    else Toast.makeText(context, "Required fields missing.", Toast.LENGTH_SHORT).show() 
                }, 
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("SAVE PROFILE", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun SosActivationButton(onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp).clickable(onClick = onClick)) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val scale by transition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
        val alpha by transition.animateFloat(0.3f, 0f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "alpha")
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = Color.Red, radius = size.minDimension / 2 * scale, alpha = alpha, style = Stroke(6.dp.toPx()))
            drawCircle(color = Color.Red, radius = size.minDimension / 2.5f * scale, alpha = alpha * 0.5f, style = Stroke(2.dp.toPx()))
        }
        
        Surface(
            shape = CircleShape,
            color = Color.Red,
            modifier = Modifier.size(180.dp),
            shadowElevation = 12.dp,
            tonalElevation = 8.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("SOS", color = Color.White, fontSize = 54.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun SosActiveIndicator(onDeactivate: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
            val transition = rememberInfiniteTransition(label = "active")
            val scale by transition.animateFloat(1f, 1.4f, infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(color = Color.Red, radius = size.minDimension / 2 * scale, alpha = 0.15f)
            }
            Surface(shape = CircleShape, color = Color.Red, modifier = Modifier.size(180.dp), shadowElevation = 16.dp) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(90.dp))
                }
            }
        }
        Spacer(Modifier.height(48.dp))
        Button(
            onClick = onDeactivate,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Text("STOP SOS & SEND RECORDING", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
    }
}

@Composable
fun RecordingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recordings = remember { mutableStateListOf<RecordingItem>() }
    LaunchedEffect(Unit) { recordings.addAll(loadRecordings(context)) }
    
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("RECORDINGS", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MicOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No recordings found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                items(recordings) { item ->
                    RecordingListItem(
                        item,
                        onDelete = { if (File(item.path).delete()) { recordings.remove(item); Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show() } },
                        onPlay = { playRecording(item.path) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun RecordingListItem(item: RecordingItem, onDelete: () -> Unit, onPlay: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPlay() },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.formattedDate, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)) }
        }
    }
}

// Helpers
fun getRequiredPermissions(): Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS)

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val service = "${context.packageName}/${SosAccessibilityService::class.java.canonicalName}"
    val enabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    if (enabled == 1) {
        val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (settingValue != null) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(settingValue)
            while (splitter.hasNext()) if (splitter.next().equals(service, ignoreCase = true)) return true
        }
    }
    return false
}

private fun startListening(context: Context, speechRecognizer: SpeechRecognizer) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    speechRecognizer.startListening(intent)
}

private fun createRecognitionListener(onActivate: () -> Unit, isSosActive: () -> Boolean, restart: () -> Unit) = object : RecognitionListener {
    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches != null) for (match in matches) if (match.contains(TARGET_VOICE_COMMAND, ignoreCase = true)) { onActivate(); return }
        if (!isSosActive()) restart()
    }
    override fun onError(error: Int) { if (!isSosActive()) restart() }
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

private fun requestCurrentLocation(context: Context, onLocationReceived: (Location?) -> Unit) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        onLocationReceived(null); return
    }
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
        .addOnSuccessListener { loc -> onLocationReceived(loc) }
        .addOnFailureListener { onLocationReceived(null) }
}

private fun sendSms(context: Context, phoneNumber: String, message: String) {
    try {
        Log.d("Kawatch", "Attempting to send SMS to: $phoneNumber")

        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val subManager = context.getSystemService(SubscriptionManager::class.java)
            val defaultSmsId = SubscriptionManager.getDefaultSmsSubscriptionId()

            if (defaultSmsId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(defaultSmsId)
            } else {
                context.getSystemService(SmsManager::class.java)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val defaultSmsId = SubscriptionManager.getDefaultSmsSubscriptionId()

            if (defaultSmsId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(defaultSmsId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        val parts = smsManager.divideMessage(message)
        smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        Log.d("Kawatch", "SMS successfully handed off to the Android system.")

    } catch (e: Exception) {
        Log.e("Kawatch", "CRITICAL: Failed to send SMS", e)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "SMS Error: Check SIM settings.", Toast.LENGTH_LONG).show()
        }
    }
}


private fun sendMmsWithAttachment(context: Context, phoneNumber: String, message: String, audioFile: File) {
    if (!audioFile.exists()) return
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", audioFile)
        val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg" 
            putExtra("address", phoneNumber)
            putExtra(Intent.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra("sms_body", message)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri(null, uri)
            
            if (defaultSmsPackage != null) {
                setPackage(defaultSmsPackage)
                context.grantUriPermission(defaultSmsPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("Kawatch", "MMS Error", e)
        Toast.makeText(context, "Could not attach recording", Toast.LENGTH_SHORT).show()
    }
}

private fun startRecording(context: Context, file: File): MediaRecorder? {
    return try {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
    } catch (e: Exception) {
        Log.e("Kawatch", "Start recording failed", e)
        null
    }
}

private fun stopRecording(recorder: MediaRecorder?) {
    try {
        recorder?.apply {
            stop()
            release()
        }
    } catch (e: Exception) {
        Log.e("Kawatch", "Stop recording failed", e)
    }
}

private fun createAudioFile(context: Context): File {
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    // Save as .mp3 extension in external cache for better compatibility
    return File.createTempFile("SOS_${timeStamp}_", ".mp3", context.externalCacheDir)
}

private fun loadRecordings(context: Context): List<RecordingItem> {
    val list = mutableListOf<RecordingItem>()
    // Look primarily in the Music directory where we now save everything
    val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
    val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

    val files = dir?.listFiles { file ->
        file.extension == "mp3" || file.extension == "mp4" || file.extension == "3gp"
    } ?: emptyArray()

    files.sortedByDescending { it.lastModified() }.forEach {
        list.add(RecordingItem(it.name, it.absolutePath, sdf.format(Date(it.lastModified()))))
    }
    return list
}

private fun playRecording(path: String) { try { MediaPlayer().apply { setDataSource(path); prepare(); start() } } catch (e: Exception) { } }
