package br.com.t4acontrol.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R

@Composable
internal fun LoginScreen(foreground: Color, onLogin: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text(stringResource(R.string.authentication), color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    TextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(R.string.email_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(R.string.password_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )
    Button(
        onClick = {
            onLogin(email.trim(), password)
            password = ""
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.login))
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun LoginScreenPreview() {
    Column(Modifier.padding(16.dp)) {
        LoginScreen(Color(0xFF101820)) { _, _ -> }
    }
}
