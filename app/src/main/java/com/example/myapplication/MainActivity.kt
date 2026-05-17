package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class HttpResponse(
    val statusCode: Int,
    val responseHeaders: Map<String, String>,
    val bodyBytes: ByteArray?,
    val contentType: String,
    val errorMessage: String? = null
) {
    val isSuccess get() = errorMessage == null
    val isImage get() = contentType.startsWith("image/")
    val bodyText get() = bodyBytes?.toString(Charsets.UTF_8) ?: ""
}

private val httpClient = OkHttpClient()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                HttpTesterScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HttpTesterScreen() {
    val methods = listOf("GET", "POST", "PUT", "DELETE")
    var selectedMethod by remember { mutableStateOf("GET") }
    var url by remember { mutableStateOf("") }
    var headersText by remember { mutableStateOf("Content-Type: application/json") }
    var bodyText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var response by remember { mutableStateOf<HttpResponse?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HTTP 请求测试工具") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 请求方法选择
            Text("请求方法", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                methods.forEachIndexed { index, method ->
                    SegmentedButton(
                        selected = selectedMethod == method,
                        onClick = { selectedMethod = method },
                        shape = SegmentedButtonDefaults.itemShape(index, methods.size),
                        label = { Text(method, fontWeight = FontWeight.Medium) }
                    )
                }
            }

            // 请求 URL
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("请求 URL") },
                placeholder = { Text("https://api.example.com/endpoint") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            // 请求头
            OutlinedTextField(
                value = headersText,
                onValueChange = { headersText = it },
                label = { Text("请求头（每行 Key: Value）") },
                placeholder = { Text("Content-Type: application/json\nAuthorization: Bearer token") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 160.dp),
                maxLines = 8,
                textStyle = LocalTextStyle.current.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            )

            // 请求体（GET/DELETE 不显示）
            if (selectedMethod == "POST" || selectedMethod == "PUT") {
                OutlinedTextField(
                    value = bodyText,
                    onValueChange = { bodyText = it },
                    label = { Text("请求体 Body") },
                    placeholder = { Text("{\"key\": \"value\"}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    maxLines = 12,
                    textStyle = LocalTextStyle.current.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                )
            }

            // 发送按钮
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        response = sendHttpRequest(selectedMethod, url, headersText, bodyText)
                        isLoading = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = url.isNotBlank() && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("发送中...", fontSize = 16.sp)
                } else {
                    Text("发 送", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    response?.let { resp ->
        ResponseDialog(response = resp, onDismiss = { response = null })
    }
}

@Composable
fun ResponseDialog(response: HttpResponse, onDismiss: () -> Unit) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = if (response.isImage) listOf("原始", "图片", "响应头") else listOf("原始", "JSON", "响应头")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏 - 显示状态码
                val isOk = response.isSuccess && response.statusCode in 200..299
                val titleBg = if (isOk)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.errorContainer
                val titleFg = if (isOk)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onErrorContainer

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(titleBg)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (response.isSuccess) "状态码: ${response.statusCode}" else "请求失败",
                        style = MaterialTheme.typography.titleMedium,
                        color = titleFg,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = titleFg)
                    }
                }

                // Tab 栏
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title, fontWeight = FontWeight.Medium) }
                        )
                    }
                }

                // Tab 内容
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    when (selectedTab) {
                        0 -> RawTab(response)
                        1 -> if (response.isImage) ImageTab(response) else JsonTab(response)
                        2 -> HeadersTab(response)
                    }
                }
            }
        }
    }
}

@Composable
fun RawTab(response: HttpResponse) {
    val text = if (response.isSuccess) {
        response.bodyText.ifEmpty { "（响应体为空）" }
    } else {
        "错误: ${response.errorMessage}"
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
fun JsonTab(response: HttpResponse) {
    val formatted = remember(response) {
        if (!response.isSuccess) {
            "错误: ${response.errorMessage}"
        } else {
            try {
                val element = JsonParser.parseString(response.bodyText)
                GsonBuilder().setPrettyPrinting().create().toJson(element)
            } catch (e: Exception) {
                "无法解析为 JSON，原始内容:\n\n${response.bodyText}"
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                Text(
                    text = formatted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
fun ImageTab(response: HttpResponse) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (response.bodyBytes != null && response.bodyBytes.isNotEmpty()) {
            AsyncImage(
                model = response.bodyBytes,
                contentDescription = "响应图片",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("无图片数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun HeadersTab(response: HttpResponse) {
    if (response.responseHeaders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("无响应头", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        response.responseHeaders.forEach { (key, value) ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    Text(
                        text = key,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = value,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

suspend fun sendHttpRequest(
    method: String,
    url: String,
    headersText: String,
    bodyText: String
): HttpResponse = withContext(Dispatchers.IO) {
    try {
        val builder = Request.Builder().url(url)

        headersText.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty()) builder.addHeader(key, value)
            }
        }

        val reqBody = bodyText
            .takeIf { it.isNotBlank() }
            ?.toRequestBody("application/json".toMediaTypeOrNull())

        when (method) {
            "GET"    -> builder.get()
            "POST"   -> builder.post(reqBody ?: "".toRequestBody(null))
            "PUT"    -> builder.put(reqBody ?: "".toRequestBody(null))
            "DELETE" -> if (reqBody != null) builder.delete(reqBody) else builder.delete()
        }

        val resp = httpClient.newCall(builder.build()).execute()
        val bytes = resp.body?.bytes()
        val contentType = resp.body?.contentType()?.toString() ?: ""
        val headers = linkedMapOf<String, String>()
        for (i in 0 until resp.headers.size) {
            headers[resp.headers.name(i)] = resp.headers.value(i)
        }

        HttpResponse(
            statusCode = resp.code,
            responseHeaders = headers,
            bodyBytes = bytes,
            contentType = contentType
        )
    } catch (e: Exception) {
        HttpResponse(
            statusCode = 0,
            responseHeaders = emptyMap(),
            bodyBytes = null,
            contentType = "",
            errorMessage = e.message ?: "未知错误"
        )
    }
}
