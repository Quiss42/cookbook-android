package de.ex_parrot.cookbook.ui.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.ex_parrot.cookbook.R
import de.ex_parrot.cookbook.ui.components.ErrorDetails
import de.ex_parrot.cookbook.ui.components.errorMessage
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.openUrl.collect { openInBrowser(context, it) }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The app icon: background colour plus foreground, as on the home screen.
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background)),
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(stringResource(R.string.login_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.login_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))

            when (val step = viewModel.step) {
                is LoginStep.WaitingForBrowser -> WaitingForBrowser(viewModel)
                LoginStep.Working -> CircularProgressIndicator()
                else -> {
                    LoginForm(viewModel)
                    if (step is LoginStep.Failed) {
                        Text(
                            errorMessage(step.error),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        ErrorDetails(step.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginForm(viewModel: LoginViewModel) {
    var advanced by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = viewModel.server,
        onValueChange = { viewModel.server = it },
        label = { Text(stringResource(R.string.login_server)) },
        placeholder = { Text("cloud.example.com") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )

    AnimatedVisibility(viewModel.useAppPassword) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = viewModel.username,
                onValueChange = { viewModel.username = it },
                label = { Text(stringResource(R.string.login_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = viewModel.appPassword,
                onValueChange = { viewModel.appPassword = it },
                label = { Text(stringResource(R.string.login_app_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    Button(
        onClick = viewModel::submit,
        enabled = viewModel.canSubmit,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (viewModel.useAppPassword) R.string.login_button else R.string.login_button_browser,
            ),
        )
    }

    TextButton(onClick = { advanced = !advanced }) {
        Text(stringResource(R.string.login_advanced))
        Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
    }
    AnimatedVisibility(advanced) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow(
                    title = stringResource(R.string.login_use_app_password),
                    subtitle = stringResource(R.string.login_use_app_password_hint),
                    checked = viewModel.useAppPassword,
                    onCheckedChange = { viewModel.useAppPassword = it },
                )
                SwitchRow(
                    title = stringResource(R.string.login_trust_all),
                    subtitle = stringResource(R.string.login_trust_all_hint),
                    checked = viewModel.trustAllCerts,
                    onCheckedChange = { viewModel.trustAllCerts = it },
                )
            }
        }
    }
}

@Composable
private fun WaitingForBrowser(viewModel: LoginViewModel) {
    CircularProgressIndicator()
    Text(stringResource(R.string.login_waiting), textAlign = TextAlign.Center)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = viewModel::cancel) { Text(stringResource(R.string.cancel)) }
        Button(onClick = viewModel::reopenBrowser) { Text(stringResource(R.string.login_open_browser)) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun openInBrowser(context: Context, url: String) {
    val uri = Uri.parse(url)
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
