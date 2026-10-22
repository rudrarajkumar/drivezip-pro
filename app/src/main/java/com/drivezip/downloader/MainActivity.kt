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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import org.json.JSONObject
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
                    MilestoneOneScreen()
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

// Built-in Google DNS-over-HTTPS (8.8.8.8) fallback so Jio ISP never blocks .trycloudflare.com
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
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

data class QualityOption(
    val quality: String,
    val bytes: Long,
    val isCopy: Boolean,
    val label: String
)

data class SingleInspectResult(
    val resourceId: String,
    val name: String,
    val mimeType: String,
    val isFolder: Boolean,
    val isVideo: Boolean,
    val isPdfOrDoc: Boolean,
    val origSizeBytes: Long,
    val width: Int,
    val height: Int,
    val durationSec: Int,
    val canDownload: Boolean,
    val qualities: List<QualityOption>
)

@Composable
fun MilestoneOneScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { getSecurePrefs(context) }

    var selectedTab by remember { mutableStateOf(0) }

    // Applied (Active) Settings vs Draft Input States so every setting has an explicit Apply button
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

    var driveLinkInput by remember { mutableStateOf("") }
    var draftQualityChoice by remember { mutableStateOf("360p") }
    var appliedQualityChoice by remember { mutableStateOf("360p") }

    var inspectResult by remember { mutableStateOf<SingleInspectResult?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf("Ready. Configure Backend & Auth in Tab 2 or paste a Google Drive link.") }

    // Launcher for Google Drive OAuth Consent Screen (Fixes the null-safety crash at line 215)
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
                            Toast.makeText(context, "Google Drive Authorized & Applied!", Toast.LENGTH_SHORT).show()
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

    // Launcher for Google Account Picker
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
            statusBanner = "Google Sign-In failed (Make sure Permanent SHA-1 is saved in Google Cloud Console): ${e.message}"
        }
    }

    // Automatically check if GitHub has a live backend URL on startup
    LaunchedEffect(Unit) {
        if (appliedBackendUrl.isBlank()) {
            val url = fetchLiveUrlUncached(appliedGhRepo)
            if (url != null) {
                appliedBackendUrl = url
                draftBackendUrl = url
                prefs.edit().putString("backend_url", url).apply()
                statusBanner = "Auto-connected to GitHub Cloud Server: $url"
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("1. Link Analyzer") })
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Google Drive Link Analyzer (Milestone 1)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                                        statusBanner = "No live_url.txt found yet. Wake Cloud Server in Tab 2."
                                    }
                                }
                            }) {
                                Text("🔄 Sync Cloud URL")
                            }
                        }

                        Text(
                            text = "Active Backend: ${if (appliedBackendUrl.isBlank()) "Not Set (Go to Tab 2)" else appliedBackendUrl}\n" +
                                   "Active Auth: ${if (appliedOauthToken.isNotBlank()) "OAuth Token (${signedInEmail.ifBlank { "Manual" }})" else if (appliedDriveApiKey.isNotBlank()) "API Key (Public Links)" else "None (Go to Tab 2)"}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )

                        OutlinedTextField(
                            value = driveLinkInput,
                            onValueChange = { driveLinkInput = it.trim() },
                            label = { Text("Paste Google Drive Video, PDF, or Folder Link") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Button(
                            onClick = {
                                isBusy = true
                                inspectResult = null
                                statusBanner = "Analyzing Google Drive metadata via Cloud Backend..."
                                scope.launch {
                                    val res = inspectSingleResource(
                                        rawUrl = appliedBackendUrl,
                                        backendKey = appliedBackendKey,
                                        driveLink = driveLinkInput,
                                        oauthToken = appliedOauthToken,
                                        driveApiKey = appliedDriveApiKey
                                    )
                                    res.onSuccess { info ->
                                        inspectResult = info
                                        statusBanner = "Analysis complete: ${info.name}"
                                    }.onFailure { err ->
                                        statusBanner = "Analysis failed: ${err.message}"
                                    }
                                    isBusy = false
                                }
                            },
                            enabled = !isBusy && driveLinkInput.isNotBlank() && appliedBackendUrl.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp)
                        ) {
                            Text(if (isBusy) "Analyzing Link..." else "🔍 Analyze Link & Show Quality Options")
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

                inspectResult?.let { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val typeBadge = when {
                                item.isVideo -> "🎬 VIDEO FILE"
                                item.isFolder -> "📂 FOLDER"
                                else -> "📄 PDF / DOCUMENT FILE"
                            }
                            Text("$typeBadge: ${item.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Drive ID: ${item.resourceId}\nMIME Type: ${item.mimeType}\nOriginal Size: ${formatSize(item.origSizeBytes)}",
                                fontFamily = FontFamily.Monospace
                            )

                            if (item.isVideo) {
                                Text(
                                    text = "Source Resolution: ${item.width}x${item.height} (${item.height}p) | Duration: ${formatDuration(item.durationSec)}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Divider()
                                Text("Select Video Quality & Tap Apply:", fontWeight = FontWeight.Bold)

                                item.qualities.forEach { qOpt ->
                                    val isSelected = draftQualityChoice == qOpt.quality
                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { draftQualityChoice = qOpt.quality }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { draftQualityChoice = qOpt.quality }
                                                )
                                                Text("${qOpt.quality} (${qOpt.label})", fontWeight = FontWeight.Bold)
                                            }
                                            Text(formatSize(qOpt.bytes), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }

                                Button(
                                    onClick = {
                                        appliedQualityChoice = draftQualityChoice
                                        statusBanner = "Applied quality: $appliedQualityChoice for ${item.name}"
                                        Toast.makeText(context, "Applied Quality: $appliedQualityChoice", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("✅ Apply Selected Quality ($draftQualityChoice) — Active: $appliedQualityChoice")
                                }
                            } else if (item.isPdfOrDoc) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "📄 Non-video file (PDF / Document). Always downloads in 100% Original Quality (${formatSize(item.origSizeBytes)}).",
                                        modifier = Modifier.padding(12.dp),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            } else {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "📂 Folder detected! Full recursive subfolder scan (separating PDFs and Videos) unlocks in Milestone 2.",
                                        modifier = Modifier.padding(12.dp),
                                        fontWeight = FontWeight.Medium
                                    )
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
                                        statusBanner = "Waking 4-CPU GitHub Cloud Server (~45s)..."
                                        val triggered = triggerGitHubBackendWorkflow(appliedGhRepo, appliedGhPat)
                                        if (!triggered) {
                                            statusBanner = "Could not trigger via token. Check PAT or start 'Run DriveZip Cloud Backend' in GitHub Actions."
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
                                                statusBanner = "Cloud Server Awake & Applied: $candidate"
                                                break
                                            }
                                        }
                                        isBusy = false
                                    }
                                },
                                enabled = !isBusy && appliedGhPat.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🚀 1-Tap Wake Cloud")
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
                                statusBanner = "Applied OAuth 2.0 Access Token (${appliedOauthToken.take(12)}...)."
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

private suspend fun inspectSingleResource(
    rawUrl: String,
    backendKey: String,
    driveLink: String,
    oauthToken: String,
    driveApiKey: String
): Result<SingleInspectResult> = withContext(Dispatchers.IO) {
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

            val qList = mutableListOf<QualityOption>()
            val qObj = json.optJSONObject("qualities")
            for (qKey in listOf("Original", "480p", "360p", "240p", "144p")) {
                val sub = qObj?.optJSONObject(qKey)
                if (sub != null) {
                    qList.add(
                        QualityOption(
                            quality = qKey,
                            bytes = sub.optLong("bytes", 0L),
                            isCopy = sub.optBoolean("is_copy", false),
                            label = sub.optString("label", "estimated")
                        )
                    )
                }
            }

            Result.success(
                SingleInspectResult(
                    resourceId = json.getString("resource_id"),
                    name = json.getString("name"),
                    mimeType = json.getString("mime_type"),
                    isFolder = json.getBoolean("is_folder"),
                    isVideo = json.getBoolean("is_video"),
                    isPdfOrDoc = json.getBoolean("is_document"),
                    origSizeBytes = json.optLong("orig_size_bytes", 0L),
                    width = json.optInt("width", 0),
                    height = json.optInt("height", 0),
                    durationSec = (json.optLong("duration_ms", 0L) / 1000L).toInt(),
                    canDownload = json.optBoolean("can_download", true),
                    qualities = qList
                )
            )
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
