package com.sarvarbek.expense_tracker.features.auth

import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.sarvarbek.expense_tracker.ui.theme.AppRadii
import com.sarvarbek.expense_tracker.ui.theme.AppSnackbar
import com.sarvarbek.expense_tracker.ui.theme.AppSpace
import com.sarvarbek.expense_tracker.ui.theme.AppTheme
import com.sarvarbek.expense_tracker.ui.theme.LinkButton
import com.sarvarbek.expense_tracker.ui.theme.PrimaryButton
import com.sarvarbek.expense_tracker.ui.theme.fieldColors
import com.sarvarbek.expense_tracker.ui.theme.fieldShape
import com.sarvarbek.expense_tracker.ui.common.t
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.NonCancellable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class Mode { SignIn, SignUp, Verify, Forgot, Reset }

/** Uzbek text for an auth failure: Supabase error codes, then network. */
fun authErrorText(e: Throwable): String = when (e) {
    is AuthRestException -> authErrorText(e.error, e.errorDescription)
    is IOException -> t("svc.family.offline") // HttpRequestException, timeouts
    else -> t("auth.err.generic")
}

fun authErrorText(code: String, message: String): String = when (code) {
    "invalid_credentials" -> t("auth.err.credentials")
    "user_already_exists", "email_exists" -> t("auth.err.exists")
    "otp_expired" -> t("auth.err.otp")
    "weak_password" -> t("auth.err.weak")
    // Per-address cooldown says "...only request this after N seconds";
    // the project-wide hourly cap says "email rate limit exceeded".
    "over_email_send_rate_limit" -> Regex("""after (\d+) seconds""").find(message)?.groupValues?.get(1)
        ?.let { t("auth.err.cooldown", it) }
        ?: t("auth.err.hourly")
    "over_request_rate_limit" -> t("auth.err.too_many")
    else -> message
}

private fun nameError(v: String) = if (v.isNotBlank()) null else t("auth.err.name")
private fun emailError(v: String) = if ('@' in v) null else t("auth.err.email")
private fun passwordError(v: String) = if (v.length >= 6) null else t("auth.err.password")
private fun codeError(v: String) = if (Regex("""^\d{6}$""").matches(v.trim())) null else t("auth.err.code")

/**
 * Sign in / sign up / 6-digit code confirm / password reset. Shown by the
 * gate while there is no session; a successful sign-in flips the gate.
 */
@Composable
fun AuthScreen(auth: Auth) {
    val c = AppTheme.colors
    val ty = MaterialTheme.typography
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var mode by rememberSaveable { mutableStateOf(Mode.SignIn) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var obscure by rememberSaveable { mutableStateOf(true) }
    var validate by rememberSaveable { mutableStateOf(false) } // show field errors after a submit
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val mail = email.trim()

    fun go(m: Mode) {
        mode = m; error = null; obscure = true; validate = false; code = ""
        if (m != Mode.Verify) password = ""
    }

    val (title, hint, action) = when (mode) {
        Mode.SignIn -> Triple(t("auth.signin"), t("auth.signin_hint"), t("auth.signin"))
        Mode.SignUp -> Triple(t("auth.signup"), t("auth.signup_hint"), t("auth.signup"))
        Mode.Verify -> Triple(t("auth.verify"), t("auth.verify_hint", mail), t("auth.confirm"))
        Mode.Forgot -> Triple(t("auth.forgot"), t("auth.forgot_hint"), t("auth.send_code"))
        Mode.Reset -> Triple(t("auth.new_password"), t("auth.reset_hint", mail), t("common.save"))
    }
    val needsEmail = mode in setOf(Mode.SignIn, Mode.SignUp, Mode.Forgot)
    // Reset reveals the password field once the code is complete.
    val needsPassword = mode in setOf(Mode.SignIn, Mode.SignUp) || (mode == Mode.Reset && code.length == 6)
    val needsCode = mode in setOf(Mode.Verify, Mode.Reset)
    val errors = listOfNotNull(
        if (mode == Mode.SignUp) nameError(name) else null,
        if (needsEmail) emailError(email) else null,
        if (needsCode) codeError(code) else null,
        if (needsPassword || mode == Mode.Reset) passwordError(password) else null,
    )

    fun submit() {
        validate = true
        if (errors.isNotEmpty()) return
        busy = true; error = null
        scope.launch {
            try {
                when (mode) {
                    Mode.SignIn -> try {
                        auth.signInWith(Email) { this.email = mail; this.password = password }
                    } catch (e: AuthRestException) {
                        if (e.errorCode != AuthErrorCode.EmailNotConfirmed) throw e
                        auth.resendEmail(OtpType.Email.SIGNUP, mail)
                        go(Mode.Verify)
                    }
                    Mode.SignUp -> {
                        val user = auth.signUpWith(Email) {
                            this.email = mail; this.password = password
                            data = buildJsonObject { put("name", name.trim()) } // profile display_name (signup trigger)
                        }
                        // Already-confirmed email: Supabase sends nothing and returns a
                        // fake user with no identities (anti-enumeration).
                        if (user?.identities?.isEmpty() == true) error = authErrorText("user_already_exists", "")
                        else if (auth.currentSessionOrNull() == null) go(Mode.Verify)
                    }
                    Mode.Verify -> auth.verifyEmailOtp(type = OtpType.Email.SIGNUP, email = mail, token = code.trim())
                    Mode.Forgot -> {
                        auth.resetPasswordForEmail(mail)
                        go(Mode.Reset)
                    }
                    // verifyEmailOtp signs in and the gate drops this screen (and its
                    // scope), so finish the password change regardless.
                    Mode.Reset -> withContext(NonCancellable) {
                        val newPassword = password
                        auth.verifyEmailOtp(type = OtpType.Email.RECOVERY, email = mail, token = code.trim())
                        auth.updateUser { this.password = newPassword }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = authErrorText(e)
            } finally {
                busy = false
            }
        }
    }

    fun resend() = scope.launch {
        try {
            auth.resendEmail(OtpType.Email.SIGNUP, mail)
            snackbar.showSnackbar(t("auth.code_resent"))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = authErrorText(e)
        }
    }

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) { AppSnackbar(it) } },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).statusBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(AppSpace.page),
        ) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(48.dp), tint = c.accent)
            Spacer(Modifier.height(AppSpace.gap))
            Text(title, style = ty.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(hint, style = ty.bodyMedium.copy(color = c.muted))
            Spacer(Modifier.height(AppSpace.section))
            if (mode == Mode.SignUp) {
                Field(name, { name = it.take(40) }, t("auth.name"), if (validate) nameError(name) else null, KeyboardType.Text, ContentType.PersonFullName, words = true)
                Spacer(Modifier.height(12.dp))
            }
            if (needsEmail) {
                Field(email, { email = it }, "Email", if (validate) emailError(email) else null, KeyboardType.Email, ContentType.EmailAddress)
                Spacer(Modifier.height(12.dp))
            }
            if (needsCode) {
                CodeBoxes(code, { code = it.filter(Char::isDigit).take(6) }, if (validate) codeError(code) else null)
                Spacer(Modifier.height(12.dp))
            }
            if (needsPassword) {
                Field(
                    password, { password = it }, if (mode == Mode.Reset) t("auth.new_password") else t("auth.password"),
                    if (validate) passwordError(password) else null, KeyboardType.Password,
                    if (mode == Mode.SignIn) ContentType.Password else ContentType.NewPassword,
                    obscure = obscure,
                    trailing = {
                        IconButton({ obscure = !obscure }) {
                            Icon(
                                if (obscure) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                if (obscure) t("auth.show_password") else t("auth.hide_password"),
                            )
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }
            error?.let { Text(it, Modifier.padding(bottom = 12.dp), style = ty.bodyMedium.copy(color = c.danger)) }
            PrimaryButton(::submit, Modifier.fillMaxWidth(), enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(action)
            }
            Spacer(Modifier.height(8.dp))
            val link = Modifier.fillMaxWidth()
            when (mode) {
                Mode.SignIn -> {
                    LinkButton({ go(Mode.SignUp) }, link) { Text(t("auth.no_account")) }
                    LinkButton({ go(Mode.Forgot) }, link) { Text(t("auth.forgot_link")) }
                }
                Mode.Verify -> {
                    LinkButton({ resend() }, link, enabled = !busy) { Text(t("auth.resend")) }
                    LinkButton({ go(Mode.SignIn) }, link) { Text(t("common.back")) }
                }
                else -> LinkButton({ go(Mode.SignIn) }, link) { Text(t("auth.to_signin")) }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboard: KeyboardType,
    autofill: ContentType,
    obscure: Boolean = false,
    words: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) = OutlinedTextField(
    value, onChange,
    Modifier.fillMaxWidth().semantics { contentType = autofill },
    label = { Text(label) },
    isError = error != null,
    supportingText = error?.let { { Text(it, color = AppTheme.colors.danger) } },
    trailingIcon = trailing,
    visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
    keyboardOptions = KeyboardOptions(capitalization = if (words) KeyboardCapitalization.Words else KeyboardCapitalization.None, keyboardType = keyboard),
    singleLine = true,
    shape = fieldShape,
    colors = fieldColors(),
)

/** Six digit boxes drawn by one text field, so paste, autofill and backspace just work. */
@Composable
private fun CodeBoxes(value: String, onChange: (String) -> Unit, error: String?) {
    val c = AppTheme.colors
    Column {
        BasicTextField(
            value, onChange,
            Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode; contentDescription = t("auth.code") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            cursorBrush = SolidColor(c.accent.copy(alpha = 0f)),
            decorationBox = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(6) { i ->
                        val active = i == value.length
                        Box(
                            Modifier.size(52.dp).background(c.card, RoundedCornerShape(AppRadii.sm))
                                .border(if (active) 2.dp else 1.dp, if (active) c.accent else c.border, RoundedCornerShape(AppRadii.sm)),
                            contentAlignment = Alignment.Center,
                        ) { Text(value.getOrNull(i)?.toString() ?: "", style = MaterialTheme.typography.headlineSmall) }
                    }
                }
            },
        )
        error?.let { Text(it, Modifier.padding(start = 16.dp, top = 4.dp), style = MaterialTheme.typography.bodySmall.copy(color = c.danger)) }
    }
}
