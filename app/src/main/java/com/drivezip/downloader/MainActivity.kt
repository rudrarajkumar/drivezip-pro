package com.drivezip.downloader

import android.accounts.Account
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MilestoneFiveScreen()
                }
            }
        }
    }
}

fun getSecurePrefs(context: Context): SharedPreferences {
    return try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "drivezip_pro_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.getSharedPreferences("drivezip_pro_fallback_prefs", Context.MODE_PRIVATE)
    }
}

object JioSafeDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: Exception) {
            resolveViaGoogleDoH(hostname).ifEmpty { throw e }
        }
    }

    private fun resolveViaGoogleDoH(hostname: String): List<InetAddress> {
        return try {
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
            val req = Request.Builder()
                .url("https://8.8.8.8/resolve?name=$hostname&type=A")
                .header("Accept", "application/dns-json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return emptyList()
                val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
                val out = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val item = answers.getJSONObject(i)
                    if (item.optInt("type") == 1) {
                        val ip = item.optString("data")
                        if (ip.isNotEmpty()) out.add(InetAddress.getByName(ip))
                    }
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

fun buildHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .dns(JioSafeDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
}

data class QualityEstimate(
    val bytes: Long,
    val isCopy: Boolean,
    val label: String
)

data class DocumentItem(
    val id: String,
    val name: String,
    val relPath: String,
    val mimeType: String,
    val origSize: Long,
    val isWorkspace: Boolean,
    val canDownload: Boolean
)

data class VideoItem(
    val id: String,
    val name: String,
    val relPath: String,
    val origSize: Long,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val canDownload: Boolean,
    val qualities: Map<String, QualityEstimate>
)

data class FolderScanSummary(
    val resourceId: String,
    val title: String,
    val isFolder: Boolean,
    val totalFiles: Int,
    val documentCount: Int,
    val documentTotalBytes: Long,
    val videoCount: Int,
    val videoTotalDurationSec: Int,
    val videoQualityTotals: Map<String, Long>,
    val documents: List<DocumentItem>,
    val videos: List<VideoItem>
)

data class ConvertedFileResult(
    val name: String,
    val relPath: String,
    val isVideo: Boolean,
    val quality: String,
    val origBytes: Long,
    val estimatedBytes: Long,
    val actualBytes: Long,
    val action: String,
    val skipped: Boolean
)

@Composable
fun MilestoneFiveScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { getSecurePrefs(context) }

    var selectedTab by remember { mutableStateOf(0) }

    // Applied vs Draft Settings
    var appliedGhRepo by remember { mutableStateOf(prefs.getString("gh_repo", "rudrarajkumar/drivezip-pro") ?: "rudrarajkumar/drivezip-pro") }
    var draftGhRepo by remember { mutableStateOf(appliedGhRepo) }

    var appliedGhPat by remember { mutableStateOf(prefs.getString("gh_pat", "") ?: "") }
    var draftGhPat by remember { mutableStateOf(appliedGhPat) }

    var appliedBackendUrl by remember { mutableStateOf(prefs.getString("backend_url", "") ?: "") }
    var draftBackendUrl by remember { mutableStateOf(appliedBackendUrl) }

    var appliedBackendKey by remember { mutableStateOf(prefs.getString("backend_key", "drivezip2026secret") ?: "drivezip2026secret") }
    var draftBackendKey by remember { mutableStateOf(appliedBackendKey) }

    var appliedDriveApiKey by remember { mutableStateOf(prefs.getString("drive_api_key", "") ?: "") }
    var draftDriveApiKey by remember { mutableStateOf(appliedDriveApiKey) }

    var appliedOauthToken by remember { mutableStateOf(prefs.getString("oauth_token", "") ?: "") }
    var draftOauthToken by remember { mutableStateOf(appliedOauthToken) }

    var signedInEmail by remember { mutableStateOf(prefs.getString("signed_in_email", "") ?: "") }
    var pendingAccount by remember { mutableStateOf<Account?>(null) }

    var driveLinkInput by remember { mutableStateOf(prefs.getString("last_drive_link", "") ?: "") }
    var folderSummary by remember { mutableStateOf<FolderScanSummary?>(null) }

    var draftBulkQuality by remember { mutableStateOf("360p") }
    var appliedBulkQuality by remember { mutableStateOf("360p") }

    var draftVideoOverrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var appliedVideoOverrides by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    var showPdfsSection by remember { mutableStateOf(false) }
    var showVideosSection by remember { mutableStateOf(false) }

    var isBusy by remember { mutableStateOf(false) }
    var isConverting by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf("Ready. Paste a Google Drive folder or file link below.") }

    // Conversion & Packaging Job States
    var activeJobId by remember { mutableStateOf("") }
    var jobState by remember { mutableStateOf("") }
    var jobOverallPercent by remember { mutableStateOf(0f) }
    var jobCurrentFile by remember { mutableStateOf("") }
    var jobProcessedFiles by remember { mutableStateOf(0) }
    var jobTotalFiles by remember { mutableStateOf(1) }
    var jobEstimatedTotalBytes by remember { mutableStateOf(0L) }
    var jobActualBytesSoFar by remember { mutableStateOf(0L) }
    var jobFileResults by remember { mutableStateOf<List<ConvertedFileResult>>(emptyList()) }
    var jobErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var zipReady by remember { mutableStateOf(false) }
    var zipFilename by remember { mutableStateOf("") }
    var zipSizeBytes by remember { mutableStateOf(0L) }

    // Tablet Local Chunked Resumable Download States
    var isDownloadingZip by remember { mutableStateOf(false) }
    var zipDownloadedBytes by remember { mutableStateOf(0L) }
    var zipDownloadStatus by remember { mutableStateOf("") }
    var savedLocalZipPath by remember { mutableStateOf("") }

    fun applyJobSnapshot(st: JSONObject) {
        activeJobId = st.optString("job_id", activeJobId)
        jobState = st.optString("state", "")
        jobCurrentFile = st.optString("current_file", "")
        jobProcessedFiles = st.optInt("processed_files", 0)
        jobTotalFiles = st.optInt("total_files", 1).coerceAtLeast(1)
        val fallbackPct = (jobProcessedFiles.toFloat() / jobTotalFiles.toFloat()) * 100f
        jobOverallPercent = st.optDouble("overall_percent", fallbackPct.toDouble()).toFloat().coerceIn(0f, 100f)
        jobEstimatedTotalBytes = st.optLong("estimated_total_bytes", 0L)
        jobActualBytesSoFar = st.optLong("actual_bytes_so_far", 0L)
        zipReady = st.optBoolean("zip_ready", false)
        zipFilename = st.optString("zip_filename", "DriveZip_Archive.zip")
        zipSizeBytes = st.optLong("zip_size_bytes", 0L)

        val resArr = st.optJSONArray("file_results") ?: JSONArray()
        val parsedResults = mutableListOf<ConvertedFileResult>()
        for (i in 0 until resArr.length()) {
            val r = resArr.getJSONObject(i)
            parsedResults.add(
                ConvertedFileResult(
                    name = r.optString("name", "file"),
                    relPath = r.optString("rel_path", ""),
                    isVideo = r.optBoolean("is_video", false),
                    quality = r.optString("quality", ""),
                    origBytes = r.optLong("orig_bytes", 0L),
                    estimatedBytes = r.optLong("estimated_bytes", 0L),
                    actualBytes = r.optLong("actual_bytes", 0L),
                    action = r.optString("action", ""),
                    skipped = r.optBoolean("skipped", false)
                )
            )
        }
        jobFileResults = parsedResults

        val errArr = st.optJSONArray("errors") ?: JSONArray()
        val parsedErrs = mutableListOf<String>()
        for (i in 0 until errArr.length()) {
            parsedErrs.add(errArr.getString(i))
        }
        jobErrors = parsedErrs
    }

    suspend fun pollJobUntilDone(jobId: String) {
        isConverting = true
        while (true) {
            val pollRes = pollConversionJobStatus(appliedBackendUrl, appliedBackendKey, jobId)
            if (pollRes.isSuccess) {
                val st = pollRes.getOrThrow()
                applyJobSnapshot(st)
                if (jobState == "COMPLETED" || jobState == "FAILED") {
                    isConverting = false
                    statusBanner = if (jobState == "COMPLETED") {
                        "✅ Cloud ZIP64 Ready: $zipFilename (${formatSize(zipSizeBytes)})"
                    } else {
                        "Job stopped: $jobCurrentFile"
                    }
                    break
                }
            }
            delay(1500)
        }
    }

    val recoveryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val acct = pendingAccount
            if (acct != null) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val token = GoogleAuthUtil.getToken(
                            context,
                            acct,
                            "oauth2:https://www.googleapis.com/auth/drive.readonly"
                        )
                        withContext(Dispatchers.Main) {
                            appliedOauthToken = token
                            draftOauthToken = token
                            prefs.edit().putString("oauth_token", token).apply()
                            statusBanner = "Google Drive Permission Granted! OAuth Token Applied."
                            Toast.makeText(context, "Google Drive Authorized!", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusBanner = "Token error after consent: ${e.message}"
                        }
                    }
                }
            }
        } else {
            statusBanner = "Google Drive permission request was cancelled."
        }
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.result
            val acct: Account? = account.account
            if (acct != null) {
                signedInEmail = account.email ?: "Signed In"
                prefs.edit().putString("signed_in_email", signedInEmail).apply()
                pendingAccount = acct
                scope.launch(Dispatchers.IO) {
                    try {
                        val token = GoogleAuthUtil.getToken(
                            context,
                            acct,
                            "oauth2:https://www.googleapis.com/auth/drive.readonly"
                        )
                        withContext(Dispatchers.Main) {
                            appliedOauthToken = token
                            draftOauthToken = token
                            prefs.edit().putString("oauth_token", token).apply()
                            statusBanner = "Signed in as $signedInEmail (OAuth Token Applied)"
                            Toast.makeText(context, "Signed in: $signedInEmail", Toast.LENGTH_SHORT).show()
                        }
                    } catch (re: UserRecoverableAuthException) {
                        val consentIntent: Intent? = re.intent
                        withContext(Dispatchers.Main) {
                            if (consentIntent != null) {
                                statusBanner = "Opening Google Drive permission screen—tap Allow..."
                                recoveryLauncher.launch(consentIntent)
                            } else {
                                statusBanner = "Consent screen intent was null."
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusBanner = "OAuth Token Error: ${e.message}"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            statusBanner = "Google Sign-In failed: ${e.message}"
        }
    }

    // Auto-sync live backend URL & Auto-reconnect to any running or completed cloud job on startup
    LaunchedEffect(Unit) {
        var activeUrl = appliedBackendUrl
        if (activeUrl.isBlank()) {
            val fetched = fetchLiveUrlUncached(appliedGhRepo)
            if (fetched != null) {
                activeUrl = fetched
                appliedBackendUrl = fetched
                draftBackendUrl = fetched
                prefs.edit().putString("backend_url", fetched).apply()
                statusBanner = "Connected to GitHub Cloud Server: $fetched"
            }
        }
        if (activeUrl.isNotBlank()) {
            val activeJobJson = fetchActiveCloudJob(activeUrl, appliedBackendKey)
            if (activeJobJson != null && activeJobJson.optBoolean("has_active", false)) {
                val jobObj = activeJobJson.getJSONObject("job")
                applyJobSnapshot(jobObj)
                val st = jobObj.optString("state", "")
                if (st in listOf("STARTING", "CONVERTING", "PACKAGING_ZIP")) {
                    statusBanner = "Reconnected to active cloud job: ${jobObj.optString("title", "")}"
                    pollJobUntilDone(jobObj.getString("job_id"))
                } else if (st == "COMPLETED") {
                    statusBanner = "✅ Found completed ZIP64 on cloud: $zipFilename (${formatSize(zipSizeBytes)})"
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("1. Scanner, Convert & ZIP") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("2. Backend & Auth Setup") })
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (selectedTab == 0) {
                // SCANNER INPUT CARD
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Google Drive Folder & File Scanner", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            OutlinedButton(onClick = {
                                scope.launch {
                                    statusBanner = "Fetching latest live backend URL from GitHub..."
                                    val url = fetchLiveUrlUncached(appliedGhRepo)
                                    if (url != null) {
                                        appliedBackendUrl = url
                                        draftBackendUrl = url
                                        prefs.edit().putString("backend_url", url).apply()
                                        val health = checkBackendHealth(url, appliedBackendKey)
                                        statusBanner = "Synced & Applied Live Backend: $url\n$health"
                                    } else {
                                        statusBanner = "No live_url.txt found. Wake Cloud Server in Tab 2."
                                    }
                                }
                            }) {
                                Text("🔄 Sync Cloud URL")
                            }
                        }

                        Text(
                            text = "Active Backend: ${if (appliedBackendUrl.isBlank()) "Not Set (Go to Tab 2)" else appliedBackendUrl}\n" +
                                   "Active Auth: ${if (appliedOauthToken.isNotBlank()) "OAuth Token (${signedInEmail.ifBlank { "Active" }})" else if (appliedDriveApiKey.isNotBlank()) "API Key (Public Links)" else "None (Go to Tab 2)"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )

                        OutlinedTextField(
                            value = driveLinkInput,
                            onValueChange = {
                                driveLinkInput = it.trim()
                                prefs.edit().putString("last_drive_link", driveLinkInput).apply()
                            },
                            label = { Text("Paste Google Drive Folder or File Link") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                isBusy = true
                                folderSummary = null
                                draftVideoOverrides = emptyMap()
                                appliedVideoOverrides = emptyMap()
                                statusBanner = "Scanning all subfolders recursively (PDFs + Videos)..."
                                scope.launch {
                                    val res = scanFolderOrFileRecursive(
                                        rawUrl = appliedBackendUrl,
                                        backendKey = appliedBackendKey,
                                        driveLink = driveLinkInput,
                                        oauthToken = appliedOauthToken,
                                        driveApiKey = appliedDriveApiKey
                                    )
                                    res.onSuccess { summary ->
                                        folderSummary = summary
                                        statusBanner = "Scan Complete: ${summary.title} (${summary.documentCount} PDFs/Docs, ${summary.videoCount} Videos)"
                                    }.onFailure { err ->
                                        statusBanner = "Scan Error: ${err.message}"
                                    }
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && !isConverting && !isDownloadingZip && driveLinkInput.isNotBlank() && appliedBackendUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text(if (isBusy) "Scanning All Subfolders..." else "🔍 Scan Folder Recursively & Calculate Sizes")
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(text = statusBanner, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // SUMMARY & CONVERSION TRIGGER CARD
                folderSummary?.let { summary ->
                    val qualitiesList = listOf("Original", "480p", "360p", "240p", "144p")

                    val liveSelectedVideosBytes = summary.videos.sumOf { v ->
                        val qOverride = draftVideoOverrides[v.id] ?: draftBulkQuality
                        if (qOverride == "SKIP") 0L
                        else v.qualities[qOverride]?.bytes ?: v.origSize
                    }
                    val liveGrandTotalBytes = summary.documentTotalBytes + liveSelectedVideosBytes
                    val tabletFreeBytes = getTabletFreeStorageBytes()

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = if (summary.isFolder) "📂 Folder Summary: ${summary.title}" else "📄 File Summary: ${summary.title}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("• Total Files Found: ${summary.totalFiles}", fontWeight = FontWeight.Bold)
                                    Text("• Non-Video Files (PDFs, Notes, Docs — Always Original Quality): ${summary.documentCount} files (${formatSize(summary.documentTotalBytes)})")
                                    Text("• Lecture Videos: ${summary.videoCount} videos (Total Duration: ${formatDuration(summary.videoTotalDurationSec)})")
                                }
                            }

                            if (summary.videoCount > 0) {
                                Divider()
                                Text("Bulk Video Quality Table (Total Size of All ${summary.videoCount} Videos):", fontWeight = FontWeight.Bold)

                                qualitiesList.forEach { qKey ->
                                    val totalVBytes = summary.videoQualityTotals[qKey] ?: 0L
                                    val grandForThisQ = summary.documentTotalBytes + totalVBytes
                                    val isSelected = draftBulkQuality == qKey
                                    val tag = if (qKey == "Original") "exact" else "estimated"

                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isConverting) { draftBulkQuality = qKey }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { draftBulkQuality = qKey },
                                                    enabled = !isConverting
                                                )
                                                Column {
                                                    Text("$qKey - ${formatSize(totalVBytes)}", fontWeight = FontWeight.Bold)
                                                    Text("($tag video total)", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                            Text(
                                                text = "Grand Total:\n${formatSize(grandForThisQ)}",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        appliedBulkQuality = draftBulkQuality
                                        statusBanner = "Applied Bulk Video Quality: $appliedBulkQuality (Grand Total: ${formatSize(liveGrandTotalBytes)})"
                                        Toast.makeText(context, "Applied Bulk Quality: $appliedBulkQuality", Toast.LENGTH_SHORT).show()
                                    },
                                    enabled = !isConverting,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("✅ Apply Bulk Video Quality ($draftBulkQuality) — Active: $appliedBulkQuality")
                                }
                            }

                            Divider()
                            Text(
                                text = "Active Grand Total (PDFs + Videos at $appliedBulkQuality): ${formatSize(liveGrandTotalBytes)}\n" +
                                       "Tablet Free Storage Available: ${formatSize(tabletFreeBytes)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (liveGrandTotalBytes < tabletFreeBytes) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )

                            Button(
                                onClick = {
                                    isConverting = true
                                    zipReady = false
                                    savedLocalZipPath = ""
                                    jobState = "STARTING"
                                    jobOverallPercent = 0f
                                    jobProcessedFiles = 0
                                    jobTotalFiles = summary.totalFiles.coerceAtLeast(1)
                                    jobFileResults = emptyList()
                                    jobErrors = emptyList()
                                    statusBanner = "Starting Cloud FFmpeg Conversion & ZIP64 Packaging..."

                                    scope.launch {
                                        val startRes = startCloudConversionJob(
                                            rawUrl = appliedBackendUrl,
                                            backendKey = appliedBackendKey,
                                            driveLink = driveLinkInput,
                                            bulkQuality = appliedBulkQuality,
                                            videoOverrides = appliedVideoOverrides,
                                            oauthToken = appliedOauthToken,
                                            driveApiKey = appliedDriveApiKey
                                        )
                                        startRes.onSuccess { jobObj ->
                                            applyJobSnapshot(jobObj)
                                            statusBanner = "Cloud Job Running: $activeJobId"
                                            pollJobUntilDone(activeJobId)
                                        }.onFailure { err ->
                                            isConverting = false
                                            statusBanner = "Failed to start job: ${err.message}"
                                        }
                                    }
                                },
                                enabled = !isConverting && !isDownloadingZip,
                                modifier = Modifier.fillMaxWidth().height(54.dp)
                            ) {
                                Text(
                                    if (isConverting) "⚙️ Cloud Processing (${String.format(Locale.US, "%.1f", jobOverallPercent)}%)..."
                                    else "⚙️ Start Cloud FFmpeg Conversion & Build ZIP64 ($appliedBulkQuality)"
                                )
                            }
                        }
                    }

                    // SEPARATED SECTION 1: PDFS & NON-VIDEO FILES
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📄 PDFs & Non-Video Files (${summary.documentCount})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { showPdfsSection = !showPdfsSection }) {
                                    Text(if (showPdfsSection) "▲ Hide PDFs" else "▼ Show PDFs")
                                }
                            }
                            if (showPdfsSection) {
                                Divider()
                                summary.documents.forEachIndexed { idx, doc ->
                                    Text(
                                        text = "${idx + 1}. ${doc.relPath} (${formatSize(doc.origSize)})",
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }

                    // SEPARATED SECTION 2: VIDEOS & INDIVIDUAL OVERRIDES
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎬 Video Files (${summary.videoCount})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { showVideosSection = !showVideosSection }) {
                                    Text(if (showVideosSection) "▲ Hide Videos" else "▼ Show Videos")
                                }
                            }
                            if (showVideosSection) {
                                Divider()
                                summary.videos.forEachIndexed { idx, vid ->
                                    val effectiveQuality = draftVideoOverrides[vid.id] ?: draftBulkQuality
                                    val estObj = vid.qualities[effectiveQuality]
                                    val displayBytes = if (effectiveQuality == "SKIP") 0L else (estObj?.bytes ?: vid.origSize)

                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("${idx + 1}. ${vid.name}", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "Path: ${vid.relPath} | Orig: ${formatSize(vid.origSize)} → $effectiveQuality: ${formatSize(displayBytes)}",
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf("Bulk", "Original", "480p", "360p", "240p", "144p", "SKIP").forEach { choice ->
                                                    val isChipSelected = if (choice == "Bulk") !draftVideoOverrides.containsKey(vid.id)
                                                    else draftVideoOverrides[vid.id] == choice

                                                    FilterChip(
                                                        selected = isChipSelected,
                                                        onClick = {
                                                            val mutable = draftVideoOverrides.toMutableMap()
                                                            if (choice == "Bulk") mutable.remove(vid.id)
                                                            else mutable[vid.id] = choice
                                                            draftVideoOverrides = mutable
                                                        },
                                                        label = { Text(choice) },
                                                        enabled = !isConverting
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        appliedVideoOverrides = draftVideoOverrides
                                        statusBanner = "Applied ${appliedVideoOverrides.size} individual video override(s)."
                                        Toast.makeText(context, "Video Overrides Applied!", Toast.LENGTH_SHORT).show()
                                    },
                                    enabled = !isConverting,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("✅ Apply Individual Video Overrides (${draftVideoOverrides.size} custom)")
                                }
                            }
                        }
                    }
                }

                // LIVE CONVERSION, PACKAGING & RESUMABLE DOWNLOAD CARD (Visible even after app restart)
                if (jobState.isNotBlank()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Cloud Conversion & ZIP64 Center", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                if (isConverting && activeJobId.isNotBlank()) {
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            cancelCloudJob(appliedBackendUrl, appliedBackendKey, activeJobId)
                                            isConverting = false
                                            jobState = "FAILED"
                                            jobCurrentFile = "🛑 Cancelled by user."
                                            statusBanner = "Cloud job cancelled."
                                        }
                                    }) {
                                        Text("🛑 Cancel Job")
                                    }
                                }
                            }

                            val prog = (jobOverallPercent / 100f).coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { prog },
                                modifier = Modifier.fillMaxWidth().height(10.dp)
                            )

                            Text(
                                text = "State: $jobState (${String.format(Locale.US, "%.1f", jobOverallPercent)}% • $jobProcessedFiles / $jobTotalFiles files done)\n" +
                                       "Live Activity: $jobCurrentFile",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )

                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Estimated Total Size: ${formatSize(jobEstimatedTotalBytes)}\n" +
                                           "Actual Converted Size: ${formatSize(jobActualBytesSoFar)}",
                                    modifier = Modifier.padding(12.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // RESUMABLE CHUNKED DOWNLOAD TO TABLET
                            if (zipReady && activeJobId.isNotBlank()) {
                                Divider()
                                Text(
                                    text = "📦 Final Unified ZIP64 Archive Ready!\n" +
                                           "File: $zipFilename (${formatSize(zipSizeBytes)})",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                if (zipSizeBytes > 0L && zipDownloadedBytes > 0L) {
                                    val dlProg = (zipDownloadedBytes.toFloat() / zipSizeBytes.toFloat()).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { dlProg },
                                        modifier = Modifier.fillMaxWidth().height(8.dp)
                                    )
                                }

                                if (zipDownloadStatus.isNotBlank()) {
                                    Text(
                                        text = zipDownloadStatus,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                Button(
                                    onClick = {
                                        val freeBytes = getTabletFreeStorageBytes()
                                        if (zipSizeBytes > freeBytes) {
                                            Toast.makeText(context, "Not enough free tablet storage!", Toast.LENGTH_LONG).show()
                                            zipDownloadStatus = "❌ Error: Need ${formatSize(zipSizeBytes)}, but only ${formatSize(freeBytes)} free."
                                            return@Button
                                        }
                                        isDownloadingZip = true
                                        scope.launch {
                                            val res = downloadZipResumableToTablet(
                                                rawUrl = appliedBackendUrl,
                                                backendKey = appliedBackendKey,
                                                jobId = activeJobId,
                                                zipFilename = zipFilename,
                                                totalBytes = zipSizeBytes,
                                                onProgress = { dlBytes, msg ->
                                                    zipDownloadedBytes = dlBytes
                                                    zipDownloadStatus = msg
                                                }
                                            )
                                            res.onSuccess { finalFile ->
                                                savedLocalZipPath = finalFile.absolutePath
                                                zipDownloadStatus = "✅ Saved to Tablet Downloads:\n${finalFile.absolutePath}"
                                                statusBanner = "ZIP Download Complete: ${finalFile.name}"
                                                Toast.makeText(context, "Saved to Downloads/DriveZip!", Toast.LENGTH_LONG).show()
                                            }.onFailure { err ->
                                                zipDownloadStatus = "⚠️ Paused / Interrupted: ${err.message}\nTap button above to Resume from ${formatSize(zipDownloadedBytes)}!"
                                            }
                                            isDownloadingZip = false
                                        }
                                    },
                                    enabled = !isDownloadingZip,
                                    modifier = Modifier.fillMaxWidth().height(54.dp)
                                ) {
                                    Text(
                                        when {
                                            isDownloadingZip -> "📥 Downloading to Tablet (${formatSize(zipDownloadedBytes)} / ${formatSize(zipSizeBytes)})..."
                                            savedLocalZipPath.isNotBlank() -> "✅ Downloaded! Tap to Re-Verify / Download Again"
                                            zipDownloadedBytes > 0L -> "▶️ Resume ZIP Download from ${formatSize(zipDownloadedBytes)}"
                                            else -> "📥 Download Final ZIP to Tablet (${formatSize(zipSizeBytes)})"
                                        }
                                    )
                                }
                            }

                            if (jobFileResults.isNotEmpty()) {
                                Divider()
                                Text("Estimated vs. Actual Size Breakdown (${jobFileResults.size} completed):", fontWeight = FontWeight.Bold)
                                jobFileResults.forEachIndexed { idx, res ->
                                    val badge = if (res.isVideo) "🎬" else "📄"
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("${idx + 1}. $badge ${res.name} [${res.action}]", fontWeight = FontWeight.SemiBold)
                                            Text(
                                                text = "ZIP Path: ${res.relPath}\n" +
                                                       "Orig: ${formatSize(res.origBytes)} | Est (${res.quality}): ${formatSize(res.estimatedBytes)} → ACTUAL: ${formatSize(res.actualBytes)}",
                                                fontFamily = FontFamily.Monospace,
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }

                            if (jobErrors.isNotEmpty()) {
                                Divider()
                                Text("Errors (${jobErrors.size}):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                jobErrors.forEach { err ->
                                    Text(err, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            } else {
                // TAB 2: BACKEND & GOOGLE AUTH SETUP WITH EXPLICIT APPLY BUTTONS
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Section 1: GitHub Cloud Backend Controller", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = draftGhRepo,
                            onValueChange = { draftGhRepo = it.trim() },
                            label = { Text("GitHub Repository (username/repo)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftGhPat,
                            onValueChange = { draftGhPat = it.trim() },
                            label = { Text("GitHub Personal Access Token (ghp_...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                appliedGhRepo = draftGhRepo
                                appliedGhPat = draftGhPat
                                prefs.edit().putString("gh_repo", appliedGhRepo).putString("gh_pat", appliedGhPat).apply()
                                statusBanner = "Applied GitHub Repo ($appliedGhRepo) & Token."
                                Toast.makeText(context, "GitHub Settings Applied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("✅ Apply GitHub Repo & Token")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    isBusy = true
                                    scope.launch {
                                        statusBanner = "Restarting 4-CPU GitHub Cloud Server with Milestone 5 code (~45s)..."
                                        val triggered = triggerGitHubBackendWorkflow(appliedGhRepo, appliedGhPat)
                                        if (!triggered) {
                                            statusBanner = "Could not trigger via token. Start 'Run DriveZip Cloud Backend' in GitHub Actions."
                                            isBusy = false
                                            return@launch
                                        }
                                        val previousUrl = fetchLiveUrlUncached(appliedGhRepo)
                                        for (i in 1..25) {
                                            delay(4000)
                                            statusBanner = "Waiting for new tunnel URL (${i * 4}s)..."
                                            val candidate = fetchLiveUrlUncached(appliedGhRepo)
                                            if (candidate != null && candidate != previousUrl) {
                                                appliedBackendUrl = candidate
                                                draftBackendUrl = candidate
                                                prefs.edit().putString("backend_url", candidate).apply()
                                                val health = checkBackendHealth(candidate, appliedBackendKey)
                                                statusBanner = "Cloud Server Awake & Applied: $candidate\n$health"
                                                break
                                            }
                                        }
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy && appliedGhPat.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🚀 1-Tap Wake / Restart Cloud")
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val url = fetchLiveUrlUncached(appliedGhRepo)
                                        if (url != null) {
                                            appliedBackendUrl = url
                                            draftBackendUrl = url
                                            prefs.edit().putString("backend_url", url).apply()
                                            val health = checkBackendHealth(url, appliedBackendKey)
                                            statusBanner = "Synced & Applied URL: $url\n$health"
                                        } else {
                                            statusBanner = "live_url.txt not found yet on $appliedGhRepo."
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🔄 Sync & Test URL")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val msg = cleanupCloudDisk(appliedBackendUrl, appliedBackendKey)
                                    isConverting = false
                                    jobState = ""
                                    zipReady = false
                                    statusBanner = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("🗑️ Reset Cloud Jobs & Free Cloud Disk Space")
                        }

                        Divider()

                        OutlinedTextField(
                            value = draftBackendUrl,
                            onValueChange = { draftBackendUrl = it.trim() },
                            label = { Text("Cloud Backend HTTPS URL (.trycloudflare.com)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = draftBackendKey,
                            onValueChange = { draftBackendKey = it.trim() },
                            label = { Text("Backend Secret Key (X-Backend-Key)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                appliedBackendUrl = draftBackendUrl
                                appliedBackendKey = draftBackendKey
                                prefs.edit()
                                    .putString("backend_url", appliedBackendUrl)
                                    .putString("backend_key", appliedBackendKey)
                                    .apply()
                                scope.launch {
                                    val health = checkBackendHealth(appliedBackendUrl, appliedBackendKey)
                                    statusBanner = "Applied Backend Connection!\n$health"
                                }
                                Toast.makeText(context, "Backend URL & Key Applied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("✅ Apply Backend URL & Secret Key")
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Section 2: Google Drive Authorization", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        val sha1 = remember { getAppSha1Fingerprint(context) }
                        Text(
                            text = "Package Name: ${context.packageName}\nPermanent SHA-1: $sha1",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { copyTextToClipboard(context, "Package Name", context.packageName) }) {
                                Text("Copy Package")
                            }
                            OutlinedButton(onClick = { copyTextToClipboard(context, "SHA-1", sha1) }) {
                                Text("Copy SHA-1")
                            }
                        }

                        Divider()
                        Text("Option A — Public ('Anyone with the link') Files & Folders:", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = draftDriveApiKey,
                            onValueChange = { draftDriveApiKey = it.trim() },
                            label = { Text("Google Drive API Key (AIza...)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                appliedDriveApiKey = draftDriveApiKey
                                prefs.edit().putString("drive_api_key", appliedDriveApiKey).apply()
                                statusBanner = "Applied Google Drive API Key."
                                Toast.makeText(context, "Google Drive API Key Applied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("✅ Apply Google Drive API Key")
                        }

                        Divider()
                        Text("Option B — Private & 'Shared with me' Folders (OAuth 2.0):", fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                                    .requestEmail()
                                    .requestScopes(Scope("https://www.googleapis.com/auth/drive.readonly"))
                                    .build()
                                signInLauncher.launch(GoogleSignIn.getClient(context, gso).signInIntent)
                            }) {
                                Text(if (signedInEmail.isBlank()) "🔐 Sign In with Google" else "Signed In: $signedInEmail")
                            }

                            if (signedInEmail.isNotBlank() || appliedOauthToken.isNotBlank()) {
                                OutlinedButton(onClick = {
                                    signedInEmail = ""
                                    appliedOauthToken = ""
                                    draftOauthToken = ""
                                    prefs.edit().remove("signed_in_email").remove("oauth_token").apply()
                                    statusBanner = "Signed out and cleared OAuth token."
                                }) {
                                    Text("Sign Out")
                                }
                            }
                        }

                        OutlinedTextField(
                            value = draftOauthToken,
                            onValueChange = { draftOauthToken = it.trim() },
                            label = { Text("OAuth 2.0 Access Token (Auto-filled on Sign In or paste ya29... token)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                appliedOauthToken = draftOauthToken
                                prefs.edit().putString("oauth_token", appliedOauthToken).apply()
                                statusBanner = "Applied OAuth 2.0 Access Token."
                                Toast.makeText(context, "OAuth Token Applied!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("✅ Apply OAuth 2.0 Token")
                        }
                    }
                }
            }
        }
    }
}

private suspend fun downloadZipResumableToTablet(
    rawUrl: String,
    backendKey: String,
    jobId: String,
    zipFilename: String,
    totalBytes: Long,
    onProgress: suspend (Long, String) -> Unit
): Result<File> = withContext(Dispatchers.IO) {
    try {
        val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val targetDir = File(downloadsRoot, "DriveZip")
        if (!targetDir.exists()) targetDir.mkdirs()

        val partFile = File(targetDir, "$zipFilename.part")
        val finalFile = File(targetDir, zipFilename)

        if (finalFile.exists() && finalFile.length() == totalBytes && totalBytes > 0L) {
            withContext(Dispatchers.Main) {
                onProgress(totalBytes, "✅ Already downloaded completely!")
            }
            return@withContext Result.success(finalFile)
        }

        var downloaded = if (partFile.exists()) partFile.length() else 0L
        if (downloaded > totalBytes && totalBytes > 0L) {
            partFile.delete()
            downloaded = 0L
        }

        val cleanUrl = rawUrl.trimEnd('/')
        val chunkSize = 16L * 1024L * 1024L // 16 MB per HTTP Range chunk
        val client = buildHttpClient()

        while (downloaded < totalBytes || totalBytes == 0L) {
            val endByte = if (totalBytes > 0L) minOf(downloaded + chunkSize - 1, totalBytes - 1) else downloaded + chunkSize - 1
            val reqBuilder = Request.Builder()
                .url("$cleanUrl/api/v1/jobs/$jobId/download")
                .addHeader("X-Backend-Key", backendKey)

            if (totalBytes > 0L) {
                reqBuilder.addHeader("Range", "bytes=$downloaded-$endByte")
            }

            var chunkSuccess = false
            var lastErr = ""

            for (attempt in 1..4) {
                try {
                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw RuntimeException("HTTP ${resp.code}")
                        }
                        val body = resp.body ?: throw RuntimeException("Empty response body")
                        RandomAccessFile(partFile, "rw").use { raf ->
                            raf.seek(downloaded)
                            val buf = ByteArray(256 * 1024)
                            val stream = body.byteStream()
                            while (true) {
                                val read = stream.read(buf)
                                if (read == -1) break
                                raf.write(buf, 0, read)
                                downloaded += read
                                val pct = if (totalBytes > 0L) (downloaded * 100L) / totalBytes else 0L
                                withContext(Dispatchers.Main) {
                                    onProgress(
                                        downloaded,
                                        "📥 Downloading to Tablet: ${formatSize(downloaded)} / ${formatSize(totalBytes)} ($pct%)"
                                    )
                                }
                            }
                        }
                        chunkSuccess = true
                    }
                    if (chunkSuccess) break
                } catch (e: Exception) {
                    lastErr = e.message ?: "Network error"
                    delay(1500L * attempt)
                }
            }

            if (!chunkSuccess) {
                return@withContext Result.failure(RuntimeException("Connection dropped ($lastErr)"))
            }
            if (totalBytes == 0L) break
        }

        if (finalFile.exists()) finalFile.delete()
        partFile.renameTo(finalFile)
        Result.success(finalFile)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun copyTextToClipboard(context: Context, label: String, value: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied!", Toast.LENGTH_SHORT).show()
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) String.format(Locale.US, "%.2f GB", mb / 1024.0)
    else String.format(Locale.US, "%.1f MB", mb)
}

private fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}

private fun getTabletFreeStorageBytes(): Long {
    return try {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        stat.availableBlocksLong * stat.blockSizeLong
    } catch (_: Exception) {
        10L * 1024L * 1024L * 1024L
    }
}

private suspend fun fetchLiveUrlUncached(repo: String): String? = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${repo.trim()}/contents/live_url.txt?t=${System.currentTimeMillis()}")
            .header("Accept", "application/vnd.github.v3.raw")
            .header("Cache-Control", "no-cache")
            .get()
            .build()
        buildHttpClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val txt = resp.body?.string()?.trim() ?: ""
            if (txt.startsWith("https://")) txt else null
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun triggerGitHubBackendWorkflow(repo: String, pat: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject().put("ref", "main").toString()
        val req = Request.Builder()
            .url("https://api.github.com/repos/${repo.trim()}/actions/workflows/run-backend.yml/dispatches")
            .header("Authorization", "Bearer ${pat.trim()}")
            .header("Accept", "application/vnd.github+json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        buildHttpClient().newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}

private suspend fun checkBackendHealth(rawUrl: String, key: String): String = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val req = Request.Builder().url("$clean/api/v1/health").addHeader("X-Backend-Key", key).get().build()
        buildHttpClient().newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful) "HEALTH OK (HTTP 200): $body" else "HTTP ${resp.code}: $body"
        }
    } catch (e: Exception) {
        "Connection Error: ${e.message}"
    }
}

private suspend fun fetchActiveCloudJob(rawUrl: String, key: String): JSONObject? = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val req = Request.Builder().url("$clean/api/v1/jobs/active").addHeader("X-Backend-Key", key).get().build()
        buildHttpClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            JSONObject(resp.body?.string() ?: "{}")
        }
    } catch (_: Exception) {
        null
    }
}

private suspend fun cancelCloudJob(rawUrl: String, key: String, jobId: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$clean/api/v1/jobs/$jobId/cancel")
            .addHeader("X-Backend-Key", key)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        buildHttpClient().newCall(req).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }
}

private suspend fun cleanupCloudDisk(rawUrl: String, key: String): String = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$clean/api/v1/jobs/cleanup")
            .addHeader("X-Backend-Key", key)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        buildHttpClient().newCall(req).execute().use { resp ->
            val b = resp.body?.string() ?: ""
            if (resp.isSuccessful) "Cleared cloud temp files! $b" else "HTTP ${resp.code}: $b"
        }
    } catch (e: Exception) {
        "Cleanup error: ${e.message}"
    }
}

private suspend fun scanFolderOrFileRecursive(
    rawUrl: String,
    backendKey: String,
    driveLink: String,
    oauthToken: String,
    driveApiKey: String
): Result<FolderScanSummary> = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val payload = JSONObject()
            .put("drive_link", driveLink)
            .put("oauth_token", oauthToken)
            .put("api_key", driveApiKey)
            .toString()

        val req = Request.Builder()
            .url("$clean/api/v1/inspect")
            .addHeader("X-Backend-Key", backendKey)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        buildHttpClient().newCall(req).execute().use { resp ->
            val str = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code}: $str"))
            val json = JSONObject(str)

            val vTotalsObj = json.getJSONObject("video_quality_totals")
            val vTotals = mutableMapOf<String, Long>()
            for (qKey in listOf("Original", "480p", "360p", "240p", "144p")) {
                vTotals[qKey] = vTotalsObj.optLong(qKey, 0L)
            }

            val docsArr = json.optJSONArray("documents") ?: JSONArray()
            val docsList = mutableListOf<DocumentItem>()
            for (i in 0 until docsArr.length()) {
                val d = docsArr.getJSONObject(i)
                docsList.add(
                    DocumentItem(
                        id = d.getString("id"),
                        name = d.getString("name"),
                        relPath = d.getString("rel_path"),
                        mimeType = d.getString("mime_type"),
                        origSize = d.optLong("orig_size", 0L),
                        isWorkspace = d.optBoolean("is_workspace", false),
                        canDownload = d.optBoolean("can_download", true)
                    )
                )
            }

            val vidsArr = json.optJSONArray("videos") ?: JSONArray()
            val vidsList = mutableListOf<VideoItem>()
            for (i in 0 until vidsArr.length()) {
                val v = vidsArr.getJSONObject(i)
                val qMap = mutableMapOf<String, QualityEstimate>()
                val qObj = v.optJSONObject("qualities")
                for (qKey in listOf("Original", "480p", "360p", "240p", "144p")) {
                    val sub = qObj?.optJSONObject(qKey)
                    if (sub != null) {
                        qMap[qKey] = QualityEstimate(
                            bytes = sub.optLong("bytes", 0L),
                            isCopy = sub.optBoolean("is_copy", false),
                            label = sub.optString("label", "estimated")
                        )
                    }
                }
                vidsList.add(
                    VideoItem(
                        id = v.getString("id"),
                        name = v.getString("name"),
                        relPath = v.getString("rel_path"),
                        origSize = v.optLong("orig_size", 0L),
                        width = v.optInt("width", 0),
                        height = v.optInt("height", 0),
                        durationSec = (v.optLong("duration_ms", 0L) / 1000L).toInt(),
                        canDownload = v.optBoolean("can_download", true),
                        qualities = qMap
                    )
                )
            }

            Result.success(
                FolderScanSummary(
                    resourceId = json.getString("resource_id"),
                    title = json.getString("title"),
                    isFolder = json.getBoolean("is_folder"),
                    totalFiles = json.getInt("total_files"),
                    documentCount = json.getInt("document_count"),
                    documentTotalBytes = json.getLong("document_total_bytes"),
                    videoCount = json.getInt("video_count"),
                    videoTotalDurationSec = json.getInt("video_total_duration_sec"),
                    videoQualityTotals = vTotals,
                    documents = docsList,
                    videos = vidsList
                )
            )
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private suspend fun startCloudConversionJob(
    rawUrl: String,
    backendKey: String,
    driveLink: String,
    bulkQuality: String,
    videoOverrides: Map<String, String>,
    oauthToken: String,
    driveApiKey: String
): Result<JSONObject> = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val overridesJson = JSONObject()
        videoOverrides.forEach { (k, v) -> overridesJson.put(k, v) }

        val payload = JSONObject()
            .put("drive_link", driveLink)
            .put("bulk_quality", bulkQuality)
            .put("video_overrides", overridesJson)
            .put("oauth_token", oauthToken)
            .put("api_key", driveApiKey)
            .toString()

        val req = Request.Builder()
            .url("$clean/api/v1/jobs/start")
            .addHeader("X-Backend-Key", backendKey)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        buildHttpClient().newCall(req).execute().use { resp ->
            val str = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code}: $str"))
            Result.success(JSONObject(str))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private suspend fun pollConversionJobStatus(
    rawUrl: String,
    backendKey: String,
    jobId: String
): Result<JSONObject> = withContext(Dispatchers.IO) {
    try {
        val clean = rawUrl.trimEnd('/')
        val req = Request.Builder()
            .url("$clean/api/v1/jobs/$jobId/status")
            .addHeader("X-Backend-Key", backendKey)
            .get()
            .build()

        buildHttpClient().newCall(req).execute().use { resp ->
            val str = resp.body?.string() ?: ""
            if (!resp.isSuccessful) return@withContext Result.failure(RuntimeException("HTTP ${resp.code}: $str"))
            Result.success(JSONObject(str))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@Suppress("DEPRECATION")
private fun getAppSha1Fingerprint(context: Context): String {
    return try {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return "Unavailable"
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString(":") { "%02X".format(it) }
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}
