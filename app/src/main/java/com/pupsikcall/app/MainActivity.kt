package com.pupsikcall.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val AppBackground = Color(0xFF111015)
private val AppSurface = Color(0xFF1D1B23)
private val FieldBackground = Color(0xFF211F27)
private val FieldBorder = Color(0xFF302D37)
private val PrimaryPurple = Color(0xFF8B5CF6)
private val LightPurple = Color(0xFFB89AFF)
private val OnlineGreen = Color(0xFF32D583)
private val DeclineRed = Color(0xFFFF5364)
private val MainText = Color(0xFFF8F7FA)
private val SecondaryText = Color(0xFFA29EAA)

private enum class DemoScreen { SignIn, Contacts, IncomingCall, ActiveCall }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AppBackground.toArgb()
        window.navigationBarColor = AppBackground.toArgb()
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent { PupsikCallApp() }
    }
}

@Composable
private fun PupsikCallApp() {
    var screen by rememberSaveable { mutableStateOf(DemoScreen.SignIn) }
    var isMuted by rememberSaveable { mutableStateOf(false) }
    var speakerEnabled by rememberSaveable { mutableStateOf(true) }

    BackHandler(enabled = screen != DemoScreen.SignIn) {
        screen = when (screen) {
            DemoScreen.ActiveCall, DemoScreen.IncomingCall -> DemoScreen.Contacts
            DemoScreen.Contacts -> DemoScreen.SignIn
            DemoScreen.SignIn -> DemoScreen.SignIn
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PrimaryPurple,
            secondary = OnlineGreen,
            background = AppBackground,
            surface = AppSurface,
            onBackground = MainText,
            onSurface = MainText,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when (screen) {
                DemoScreen.SignIn -> SignInScreen { screen = DemoScreen.Contacts }
                DemoScreen.Contacts -> ContactsScreen(
                    onContactSelected = { screen = DemoScreen.IncomingCall },
                    onCall = { screen = DemoScreen.ActiveCall },
                )
                DemoScreen.IncomingCall -> IncomingCallScreen(
                    onDecline = { screen = DemoScreen.Contacts },
                    onAnswer = { screen = DemoScreen.ActiveCall },
                )
                DemoScreen.ActiveCall -> ActiveCallScreen(
                    isMuted = isMuted,
                    speakerEnabled = speakerEnabled,
                    onBack = { screen = DemoScreen.Contacts },
                    onToggleMute = { isMuted = !isMuted },
                    onToggleSpeaker = { speakerEnabled = !speakerEnabled },
                    onEndCall = { screen = DemoScreen.Contacts },
                )
            }
        }
    }
}

@Composable
private fun SignInScreen(onSignIn: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("Pupsik") }
    var password by rememberSaveable { mutableStateOf("pupsik") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark()
        Spacer(Modifier.height(20.dp))
        Text("PupsikCall", color = MainText, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text("Simple. Private. Free Calls.", color = SecondaryText, fontSize = 15.sp)
        Spacer(Modifier.height(34.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            placeholder = { Text("Username", fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.size(21.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            colors = signInFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
        Spacer(Modifier.height(13.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth().height(60.dp),
            placeholder = { Text("Password", fontSize = 15.sp) },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(21.dp)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(18.dp),
            colors = signInFieldColors(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryPurple),
        ) {
            Text("Sign In", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(17.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Don't have an account?", color = SecondaryText, fontSize = 13.sp)
            TextButton(onClick = {}) {
                Text("Create Account", color = LightPurple, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun signInFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MainText,
    unfocusedTextColor = MainText,
    focusedContainerColor = FieldBackground,
    unfocusedContainerColor = FieldBackground,
    focusedBorderColor = PrimaryPurple,
    unfocusedBorderColor = FieldBorder,
    focusedPlaceholderColor = SecondaryText,
    unfocusedPlaceholderColor = SecondaryText,
    focusedLeadingIconColor = SecondaryText,
    unfocusedLeadingIconColor = SecondaryText,
    cursorColor = PrimaryPurple,
)

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier.size(102.dp).clip(CircleShape).background(PrimaryPurple),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(47.dp))
        Canvas(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 17.dp).size(20.dp)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color.White, -52f, 104f, false, style = stroke)
            drawArc(
                Color.White,
                -52f,
                104f,
                false,
                topLeft = androidx.compose.ui.geometry.Offset(4.dp.toPx(), 4.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(size.width - 8.dp.toPx(), size.height - 8.dp.toPx()),
                style = stroke,
            )
        }
    }
}

@Composable
private fun ContactsScreen(onContactSelected: () -> Unit, onCall: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 23.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(68.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Contacts", color = MainText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            IconButton(onClick = {}, modifier = Modifier.size(48.dp)) {
                PersonAddGlyph()
            }
        }
        Spacer(Modifier.height(11.dp))
        ContactRow("T", "Tanya", "Online", true, onContactSelected, onCall)
        Spacer(Modifier.height(8.dp))
        ContactRow("P", "Pupsik2", "Offline", false, onContactSelected, onCall)
        Spacer(Modifier.weight(1f))
        BottomNavigationBar()
    }
}

@Composable
private fun PersonAddGlyph() {
    Box(Modifier.size(27.dp)) {
        Icon(Icons.Filled.Person, contentDescription = "Add contact", tint = MainText, modifier = Modifier.align(Alignment.CenterStart).size(24.dp))
        Canvas(Modifier.align(Alignment.BottomEnd).size(12.dp)) {
            drawCircle(AppBackground)
            val strokeWidth = 1.7.dp.toPx()
            drawLine(MainText, Offset(size.width * 0.5f, size.height * 0.18f), Offset(size.width * 0.5f, size.height * 0.82f), strokeWidth, cap = StrokeCap.Round)
            drawLine(MainText, Offset(size.width * 0.18f, size.height * 0.5f), Offset(size.width * 0.82f, size.height * 0.5f), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun ContactRow(
    initial: String,
    name: String,
    status: String,
    online: Boolean,
    onSelect: () -> Unit,
    onCall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(82.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(initial, 55.dp, online)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.Center) {
            Text(name, color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) OnlineGreen else Color(0xFF77737D)))
                Spacer(Modifier.size(6.dp))
                Text(status, color = if (online) OnlineGreen else SecondaryText, fontSize = 12.sp)
            }
        }
        IconButton(
            onClick = onCall,
            modifier = Modifier.size(48.dp).clip(CircleShape).background(OnlineGreen),
        ) {
            Icon(Icons.Filled.Call, contentDescription = "Call $name", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ProfileAvatar(initial: String, size: Dp, isTanya: Boolean) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (isTanya) Brush.linearGradient(listOf(Color(0xFF755A88), Color(0xFF3B344A)))
                else Brush.linearGradient(listOf(Color(0xFF77757E), Color(0xFF54525B))),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = if (size >= 120.dp) 48.sp else 22.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun BottomNavigationBar() {
    Column {
        HorizontalDivider(color = FieldBorder, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavigationItem("Contacts", true, Icons.Filled.Person)
            BottomNavigationItem("Settings", false, Icons.Filled.Settings)
        }
    }
}

@Composable
private fun BottomNavigationItem(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val itemColor = if (selected) PrimaryPurple else SecondaryText
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = itemColor, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = itemColor, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun IncomingCallScreen(onDecline: () -> Unit, onAnswer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1D1922), AppBackground, AppBackground)))
            .padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar("T", 148.dp, true)
            Spacer(Modifier.height(22.dp))
            Text("Tanya", color = MainText, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Incoming call...", color = SecondaryText, fontSize = 16.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            CallActionButton("Decline", DeclineRed, onDecline, rotatePhone = true)
            CallActionButton("Answer", OnlineGreen, onAnswer)
        }
    }
}

@Composable
private fun CallActionButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    rotatePhone: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(color),
        ) {
            Icon(
                Icons.Filled.Call,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(29.dp).then(if (rotatePhone) Modifier.rotate(135f) else Modifier),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(label, color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ActiveCallScreen(
    isMuted: Boolean,
    speakerEnabled: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = MainText, modifier = Modifier.size(24.dp))
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ProfileAvatar("T", 142.dp, true)
            Spacer(Modifier.height(19.dp))
            Text("Tanya", color = MainText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text("00:12", color = SecondaryText, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(39.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControl("Mute", isMuted, onToggleMute) { MicrophoneGlyph(isMuted) }
                CallControl("Speaker", speakerEnabled, onToggleSpeaker) { SpeakerGlyph() }
                CallControl("More", false, {}) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null, tint = Color.White, modifier = Modifier.size(23.dp))
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = onEndCall,
                modifier = Modifier.size(76.dp).clip(CircleShape).background(DeclineRed),
            ) {
                Icon(Icons.Filled.Call, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(31.dp).rotate(135f))
            }
            Spacer(Modifier.height(8.dp))
            Text("End Call", color = MainText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CallControl(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val controlColor = if (selected) PrimaryPurple else Color(0xFF302D36)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(controlColor)
                .then(if (selected) Modifier.border(1.dp, LightPurple.copy(alpha = 0.65f), CircleShape) else Modifier),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = SecondaryText, fontSize = 12.sp)
    }
}

@Composable
private fun MicrophoneGlyph(muted: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val strokeWidth = 2.dp.toPx()
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        drawRoundRect(
            Color.White,
            topLeft = Offset(size.width * 0.38f, size.height * 0.12f),
            size = Size(size.width * 0.24f, size.height * 0.48f),
            cornerRadius = CornerRadius(size.width * 0.12f),
            style = stroke,
        )
        drawArc(
            Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.2f, size.height * 0.32f),
            size = Size(size.width * 0.6f, size.height * 0.46f),
            style = stroke,
        )
        drawLine(Color.White, Offset(size.width * 0.5f, size.height * 0.78f), Offset(size.width * 0.5f, size.height * 0.93f), strokeWidth, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(size.width * 0.3f, size.height * 0.94f), Offset(size.width * 0.7f, size.height * 0.94f), strokeWidth, cap = StrokeCap.Round)
        if (muted) {
            drawLine(Color.White, Offset(size.width * 0.12f, size.height * 0.12f), Offset(size.width * 0.88f, size.height * 0.88f), strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun SpeakerGlyph() {
    Canvas(Modifier.size(24.dp)) {
        val speaker = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.4f)
            lineTo(size.width * 0.32f, size.height * 0.4f)
            lineTo(size.width * 0.56f, size.height * 0.2f)
            lineTo(size.width * 0.56f, size.height * 0.8f)
            lineTo(size.width * 0.32f, size.height * 0.6f)
            lineTo(size.width * 0.12f, size.height * 0.6f)
            close()
        }
        drawPath(speaker, Color.White)
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color.White, -52f, 104f, false, topLeft = Offset(size.width * 0.4f, size.height * 0.25f), size = Size(size.width * 0.45f, size.height * 0.5f), style = stroke)
        drawArc(Color.White, -52f, 104f, false, topLeft = Offset(size.width * 0.43f, size.height * 0.08f), size = Size(size.width * 0.58f, size.height * 0.84f), style = stroke)
    }
}