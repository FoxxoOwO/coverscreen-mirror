package com.example.coverscreenmirror

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.example.coverscreenmirror.theme.CoverScreenMirrorTheme
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {

    private var targetGoToHome = false
    private var isAutoStarting = false
    val refreshPermissionsTrigger = mutableStateOf(0)

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if ((result.resultCode == RESULT_OK) && (result.data != null)) {
            startMirrorService(result.resultCode, result.data!!)
            launchCoverScreenActivity("MIRRORING")
            
            if (targetGoToHome) {
                thread {
                    try {
                        Thread.sleep(800) // Wait 800ms to let projection activate completely
                    } catch (_: Exception) {}
                    runOnUiThread {
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(homeIntent)
                            android.util.Log.e("ScreenMirror", "Sent HOME intent to minimize app")
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoverScreenMirrorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212) // Dark background
                ) {
                    AppScreen(
                        activity = this,
                        onStartMirror = { goToHome -> startMirroring(goToHome) },
                        onStopMirror = { stopMirroring() }
                    )
                }
            }
        }
        handleAutoStartIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutoStartIntent(intent)
    }

    private fun handleAutoStartIntent(intent: Intent) {
        if (intent.getBooleanExtra("AUTO_START_MIRROR", false)) {
            if (isAutoStarting || CoverScreenActivity.isRunningOnCover) {
                return
            }
            
            isAutoStarting = true
            // Check if Shizuku is ready for "Main Screen" mode (most reliable for auto-start)
            if (Shizuku.pingBinder() && checkShizukuPermission()) {
                thread {
                    try {
                        // 1. Cleanup zombies (Safer version: only if needed)
                        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                        method.isAccessible = true
                        
                        // We skip pkill here to avoid destabilizing the system if multiple threads run
                        
                        // 2. Unfold state
                        val proc = method.invoke(null, arrayOf("sh", "-c", "cmd device_state state 4"), null, null) as Process
                        proc.waitFor()
                        Thread.sleep(500)
                    } catch (_: Exception) {}
                    
                    runOnUiThread {
                        isAutoStarting = false
                        launchCoverScreenActivity("SILENT_MIRRORING")
                    }
                }
            } else {
                isAutoStarting = false
                // Fallback to normal mirroring if Shizuku is not ready
                startMirroring(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh permissions when returning to app
        refreshPermissionsTrigger.value++
        
        val currentDisplay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            this.display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        if (currentDisplay?.displayId == 0) {
            CoverScreenAccessibilityService.instance?.showNavigationBar(false)
            thread {
                try {
                    if (Shizuku.pingBinder()) {
                        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                        method.isAccessible = true
                        val process = method.invoke(null, arrayOf("sh", "-c", "wm size -d 1 reset"), null, null) as Process
                        process.waitFor()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startMirroring(goToHome: Boolean = false) {
        targetGoToHome = goToHome
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                mpm.createScreenCaptureIntent(config)
            } catch (_: Throwable) {
                mpm.createScreenCaptureIntent()
            }
        } else {
            mpm.createScreenCaptureIntent()
        }
        screenCaptureLauncher.launch(intent)
    }

    private fun stopMirroring() {
        stopService(Intent(this, ScreenMirrorService::class.java))
        CoverScreenAccessibilityService.instance?.showNavigationBar(false)
        Toast.makeText(this, getString(R.string.mirroring_stopped), Toast.LENGTH_SHORT).show()
        thread {
            try {
                if (Shizuku.pingBinder()) {
                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                    method.isAccessible = true
                    // 1. Reset Display 1 override size
                    var process = method.invoke(null, arrayOf("sh", "-c", "wm size -d 1 reset"), null, null) as Process
                    process.waitFor()
                    // 2. Cancel device state overrides
                    process = method.invoke(null, arrayOf("sh", "-c", "cmd device_state state 0 && sleep 0.1 && cmd device_state cancel"), null, null) as Process
                    process.waitFor()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun startMirrorService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenMirrorService::class.java).apply {
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA", data)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    fun launchCoverScreenActivity(mode: String) {
        val displayManager = getSystemService(DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val coverDisplay = displayManager.getDisplay(1) ?: displayManager.displays.firstOrNull { it.displayId != 0 }
        
        if (coverDisplay != null) {
            try {
                val options = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    options.launchDisplayId = coverDisplay.displayId
                }
                val intent = Intent(this, CoverScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("MODE", mode)
                }
                startActivity(intent, options.toBundle())
                android.util.Log.e("ScreenMirror", "CoverScreenActivity launched via ActivityOptions on display ${coverDisplay.displayId} with mode $mode")
                Toast.makeText(this, getString(R.string.mirroring_started), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("ScreenMirror", "Failed to launch via ActivityOptions", e)
            }
        } else {
            android.util.Log.e("ScreenMirror", "Cover display not found")
        }

        // Apply device_state state 4 in background if Shizuku is running
        thread {
            try {
                if (Shizuku.pingBinder()) {
                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                    method.isAccessible = true
                    method.invoke(null, arrayOf("sh", "-c", "cmd device_state state 4"), null, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        thread {
            try {
                if (Shizuku.pingBinder()) {
                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                    method.isAccessible = true
                    val process = method.invoke(null, arrayOf("sh", "-c", "cmd device_state state 0 && sleep 0.1 && cmd device_state cancel"), null, null) as Process
                    process.waitFor()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        super.onDestroy()
    }
}

@Composable
fun AppScreen(activity: MainActivity, onStartMirror: (Boolean) -> Unit, onStopMirror: () -> Unit) {
    val prefs = activity.getSharedPreferences("mirror_prefs", Context.MODE_PRIVATE)
    var controlMode by remember { mutableStateOf(prefs.getString("control_mode", "shizuku") ?: "shizuku") }
    
    var shizukuAvailable by remember { mutableStateOf(Shizuku.pingBinder()) }
    var hasShizukuPermission by remember { mutableStateOf(checkShizukuPermission()) }
    var accessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(activity)) }
    var overlayPermissionGranted by remember { mutableStateOf<Boolean>(isOverlayPermissionGranted(activity)) }

    var autoMirrorEnabled by remember { mutableStateOf(prefs.getBoolean("auto_mirror", false)) }

    LaunchedEffect(activity.refreshPermissionsTrigger.value) {
        accessibilityEnabled = isAccessibilityServiceEnabled(activity)
        overlayPermissionGranted = isOverlayPermissionGranted(activity)
        shizukuAvailable = Shizuku.pingBinder()
        hasShizukuPermission = checkShizukuPermission()
    }

    var showConfirmDialog by remember { mutableStateOf(false) }
    var showMainConfirmDialog by remember { mutableStateOf(false) }
    var targetGoToHome by remember { mutableStateOf(false) }

    LaunchedEffect(controlMode) {
        prefs.edit { putString("control_mode", controlMode) }
    }

    LaunchedEffect(autoMirrorEnabled) {
        prefs.edit { putBoolean("auto_mirror", autoMirrorEnabled) }
    }

    LaunchedEffect(Unit) {
        val binderReceivedListener = Shizuku.OnBinderReceivedListener {
            shizukuAvailable = true
            hasShizukuPermission = checkShizukuPermission()
        }
        val binderDeadListener = Shizuku.OnBinderDeadListener {
            shizukuAvailable = false
            hasShizukuPermission = false
        }
        val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                hasShizukuPermission = true
            }
        }
        
        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionListener)
        
        if (shizukuAvailable && !hasShizukuPermission) {
            try { Shizuku.requestPermission(0) } catch (_: Exception) {}
        }
    }

    LaunchedEffect(hasShizukuPermission) {
        if (hasShizukuPermission) {
            thread {
                try {
                    val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                    @Suppress("DEPRECATION")
                    val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            "android:project_media",
                            android.os.Process.myUid(),
                            activity.packageName
                        )
                    } else {
                        appOps.checkOpNoThrow(
                            "android:project_media",
                            android.os.Process.myUid(),
                            activity.packageName
                        )
                    }
                    if (mode != android.app.AppOpsManager.MODE_ALLOWED) {
                        android.util.Log.e("ScreenMirror", "PROJECT_MEDIA not allowed, granting via Shizuku...")
                        val cmd = "appops set com.example.coverscreenmirror PROJECT_MEDIA allow"
                        val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                        method.isAccessible = true
                        val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                        process.waitFor()
                    } else {
                        android.util.Log.e("ScreenMirror", "PROJECT_MEDIA already allowed, skipping Shizuku command")
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    // Confirmation Dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.confirm_mirroring_title),
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = Color.Black
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.confirm_mirroring_text),
                    color = Color.DarkGray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        thread {
                            try {
                                if (Shizuku.pingBinder()) {
                                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                                    method.isAccessible = true
                                    val process = method.invoke(null, arrayOf("sh", "-c", "cmd device_state state 4"), null, null) as Process
                                    process.waitFor()
                                    Thread.sleep(500) // Delay 500ms to let display state stabilize
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            activity.runOnUiThread {
                                onStartMirror(targetGoToHome)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                ) {
                    Text(stringResource(R.string.yes), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        thread {
                            try {
                                if (Shizuku.pingBinder()) {
                                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                                    method.isAccessible = true
                                    val process = method.invoke(null, arrayOf("sh", "-c", "cmd device_state cancel"), null, null) as Process
                                    process.waitFor()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black)
                ) {
                    Text(stringResource(R.string.no), color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            containerColor = Color(0xFFF2F2F7), // iOS System Background
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
        )
    }

    if (showMainConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showMainConfirmDialog = false },
            title = { Text(stringResource(R.string.confirm_main_screen_title)) },
            text = { Text(stringResource(R.string.confirm_main_screen_text)) },
            containerColor = Color(0xFFF2F2F7), // iOS System Background
            titleContentColor = Color.Black,
            textContentColor = Color.DarkGray,
            confirmButton = {
                Button(
                    onClick = {
                        showMainConfirmDialog = false
                        thread {
                            try {
                                if (Shizuku.pingBinder()) {
                                    val method = Class.forName("rikka.shizuku.Shizuku").getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                                    method.isAccessible = true
                                    
                                    // 1. Cleanup zombies
                                    val cleanupProc = method.invoke(null, arrayOf("sh", "-c", "pkill -f mirror_service"), null, null) as Process
                                    cleanupProc.waitFor()
                                    Thread.sleep(200)

                                    // 2. Unfold state
                                    val proc = method.invoke(null, arrayOf("sh", "-c", "cmd device_state state 4"), null, null) as Process
                                    proc.waitFor()
                                    Thread.sleep(500)
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            activity.runOnUiThread {
                                activity.launchCoverScreenActivity("SILENT_MIRRORING")
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black)
                ) {
                    Text(stringResource(R.string.yes), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showMainConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black)
                ) {
                    Text(stringResource(R.string.no), color = Color.Black, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        )
    }

    accessibilityEnabled = isAccessibilityServiceEnabled(activity)

    // Premium light-themed, scrollable and compact layout
    val scrollState = androidx.compose.foundation.rememberScrollState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF2F2F7)) // iOS Light Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Push layout down a bit
            Spacer(modifier = Modifier.height(24.dp))
            
            // Header Row: Title & Status Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mirror_screen_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.Black,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.mirror_screen_subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.DarkGray
                    )
                }
                
                // Status Pill in Top-Right
                val isReady = if (controlMode == "shizuku") {
                    shizukuAvailable && hasShizukuPermission
                } else {
                    accessibilityEnabled
                }
                
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isReady) Color(0x2234C759) else Color(0x22FF3B30),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = if (isReady) Color(0xFF34C759) else Color(0xFFFF3B30),
                                    shape = androidx.compose.foundation.shape.CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isReady) stringResource(R.string.ready) else stringResource(R.string.not_ready),
                            color = if (isReady) Color(0xFF34C759) else Color(0xFFFF8282),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            // Permissions Status Section
            val shizukuReady = !((controlMode == "shizuku") && (!shizukuAvailable || !hasShizukuPermission))
            if (!shizukuReady || !accessibilityEnabled || !overlayPermissionGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFEBEB) // Light red alert background
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF3B30))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.perm_required_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFFFF3B30),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.perm_required_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Status items
                        PermissionStatusItem(stringResource(R.string.perm_overlay), overlayPermissionGranted)
                        PermissionStatusItem(stringResource(R.string.perm_accessibility), accessibilityEnabled)
                        if (controlMode == "shizuku") {
                            PermissionStatusItem(stringResource(R.string.perm_shizuku), shizukuAvailable && hasShizukuPermission)
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Button(
                            onClick = {
                                if (!overlayPermissionGranted) {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${activity.packageName}".toUri())
                                        activity.startActivity(intent)
                                    } catch (_: Exception) {
                                        val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                        activity.startActivity(intent)
                                    }
                                } else if (!accessibilityEnabled) {
                                    try {
                                        activity.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    } catch (_: Exception) {}
                                } else if (controlMode == "shizuku" && !hasShizukuPermission) {
                                    try { Shizuku.requestPermission(0) } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF3B30)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        ) {
                            Text(stringResource(R.string.perm_grant_all), color = Color.White)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Premium Control Mode Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White // Pure white card
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = Color(0xFFE5E5EA) // Light border
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.control_mode_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.DarkGray,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (controlMode == "accessibility"),
                            onClick = { controlMode = "accessibility" },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color.Black, // Monochrome
                                unselectedColor = Color(0xFFC7C7CC)
                            )
                        )
                        Text(
                            text = stringResource(R.string.accessibility_mode),
                            color = Color.Black,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .clickable { controlMode = "accessibility" }
                        )
                        
                        Spacer(modifier = Modifier.width(32.dp))
                        
                        RadioButton(
                            selected = (controlMode == "shizuku"),
                            onClick = { controlMode = "shizuku" },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = Color.Black, // Monochrome
                                unselectedColor = Color(0xFFC7C7CC)
                            )
                        )
                        Text(
                            text = stringResource(R.string.shizuku_mode),
                            color = Color.Black,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier
                                .padding(start = 2.dp)
                                .clickable { controlMode = "shizuku" }
                        )
                    }

                    // Dynamic warning and settings triggers
                    if (controlMode == "shizuku" && (!shizukuAvailable || !hasShizukuPermission)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.HorizontalDivider(color = Color(0xFFE5E5EA))
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (shizukuAvailable) stringResource(R.string.shizuku_not_authorized) else stringResource(R.string.shizuku_not_running),
                                    color = Color(0xFFFF453A),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Text(
                                    text = if (shizukuAvailable) stringResource(R.string.shizuku_please_authorize) else stringResource(R.string.shizuku_please_start),
                                    color = Color(0xFF8E8E93),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            if (shizukuAvailable) {
                                Button(
                                    onClick = { try { Shizuku.requestPermission(0) } catch (e: Exception) {} },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(stringResource(R.string.grant_permission), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else if (controlMode == "accessibility" && !accessibilityEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        androidx.compose.material3.HorizontalDivider(color = Color(0xFFE5E5EA))
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.accessibility_not_enabled),
                                    color = Color(0xFFFF453A),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.accessibility_requirement_desc),
                                    color = Color(0xFF8E8E93),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        activity.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(activity, activity.getString(R.string.cannot_open_settings), Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(stringResource(R.string.enable_in_settings), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Automation Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = Color(0xFFE5E5EA)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.auto_mirror_header),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.DarkGray,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        letterSpacing = androidx.compose.ui.unit.TextUnit(1.2f, androidx.compose.ui.unit.TextUnitType.Sp)
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoMirrorEnabled = !autoMirrorEnabled },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.auto_mirror_toggle),
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.auto_mirror_desc),
                                color = Color.DarkGray,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Switch(
                            checked = autoMirrorEnabled,
                            onCheckedChange = { autoMirrorEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color.Black,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFFE5E5EA)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action Buttons in a single horizontal row (3 buttons)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button 1: Phản chiếu
                Button(
                    onClick = {
                        targetGoToHome = false
                        showConfirmDialog = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black // Monochrome
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_mirror),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Button 2: Màn Chính
                Button(
                    onClick = {
                        showMainConfirmDialog = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Black // Monochrome
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_main_screen),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Button 3: Dừng
                Button(
                    onClick = onStopMirror,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White // Monochrome inverted
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.Black),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_stop),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Black, // Black text
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

fun checkShizukuPermission(): Boolean {
    if (!Shizuku.pingBinder()) return false
    return if (Shizuku.isPreV11()) {
        false
    } else {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun PermissionStatusItem(label: String, granted: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (granted) Color(0xFF34C759) else Color(0xFFFF3B30)
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) Color.DarkGray else Color.Black,
            fontWeight = if (granted) androidx.compose.ui.text.font.FontWeight.Normal else androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponentName = android.content.ComponentName(context, CoverScreenAccessibilityService::class.java)
    val enabledServicesSetting = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledService = android.content.ComponentName.unflattenFromString(componentNameString)
        if (enabledService != null && enabledService == expectedComponentName) {
            return true
        }
    }
    return false
}

fun isOverlayPermissionGranted(context: Context): Boolean {
    return android.provider.Settings.canDrawOverlays(context)
}
