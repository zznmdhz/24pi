package com.twentyfourpi.lifelog.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun AiSettingsScreen(onBack: () -> Unit) {
    val vm: AiViewModel = viewModel()
    DisposableEffect(vm) { onDispose { vm.cancel() } }
    var config by remember { mutableStateOf(vm.config()) }
    AiSettingsContent(config, onBack, { base, model, key, tokenParameter ->
        val message = vm.saveConfig(base, model, key, tokenParameter)
        config = vm.config()
        message
    }, { val message = vm.clearKey(); config = vm.config(); message }, vm::testConnection)
}

@Composable
internal fun AiSettingsContent(
    config: com.twentyfourpi.lifelog.ai.AiConfig,
    onBack: () -> Unit,
    onSave: (String, String, String?, String) -> String,
    onClear: () -> String,
    onTest: ((String) -> Unit) -> Unit,
) {
    var base by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var model by remember(config.model) { mutableStateOf(config.model) }
    var tokenParameter by remember(config.tokenParameter) { mutableStateOf(config.tokenParameter) }
    var tokenMenuOpen by remember { mutableStateOf(false) }
    // Intentionally ephemeral: never save key input in SavedState or Compose saveable state.
    var key by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
            Text("AI 配置", style = MaterialTheme.typography.headlineSmall)
        }
        Text("使用你自己的 OpenAI 兼容 Chat Completions 服务。只有你确认发送后才会请求服务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("接口协议：Chat Completions", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(base, { base = it }, modifier = Modifier.fillMaxWidth(), label = { Text("HTTPS 服务地址") }, singleLine = true, supportingText = { Text("示例：https://example.com/v1") })
        OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称") }, singleLine = true)
        Box {
            OutlinedButton(onClick = { tokenMenuOpen = true }) { Text("Token 参数：$tokenParameter ▾") }
            DropdownMenu(expanded = tokenMenuOpen, onDismissRequest = { tokenMenuOpen = false }) {
                DropdownMenuItem(text = { Text("兼容服务 · max_tokens") }, onClick = { tokenParameter = "max_tokens"; tokenMenuOpen = false })
                DropdownMenuItem(text = { Text("OpenAI 推理模型 · max_completion_tokens") }, onClick = { tokenParameter = "max_completion_tokens"; tokenMenuOpen = false })
            }
        }
        OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (config.hasKey) "API Key（已保存；留空则保留）" else "API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("API Key 由设备密钥加密，设备迁移后需重新输入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { message = onSave(base, model, key.takeIf { it.isNotBlank() }, tokenParameter); if (message.startsWith("已保存")) key = "" }, modifier = Modifier.fillMaxWidth()) { Text("保存配置") }
        Text("连接测试使用已保存的配置；编辑后请先保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { onTest { message = it } }, enabled = base == config.baseUrl && model == config.model && tokenParameter == config.tokenParameter && key.isBlank(), modifier = Modifier.fillMaxWidth()) { Text("测试连接（不发送生活记录）") }
        if (config.hasKey) TextButton(onClick = { message = onClear(); key = "" }) { Text("清除 API Key") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}
