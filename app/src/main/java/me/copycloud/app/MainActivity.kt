package me.copycloud.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Ink = Color(0xFF10191A)
private val Lime = Color(0xFFC8FF77)
private val Forest = Color(0xFF24413B)
private val Paper = Color(0xFFF3F5EF)
private val Dark = Color(0xFF111918)
private val corners = RoundedCornerShape(26.dp)

class MainActivity : ComponentActivity() {
    private lateinit var link: Link
    private val focused = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        link = Link(Build.MODEL)
        setContent { CopyCloud(link, focused.value) }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused.value = hasFocus
    }
    override fun onDestroy() { link.close(); super.onDestroy() }
}

private enum class Page(val label: String, val icon: ImageVector) {
    Home("Overview", Icons.Outlined.Home),
    Connect("Connect", Icons.Outlined.Devices),
    Clipboard("Clipboard", Icons.Outlined.ContentCopy),
    Settings("Settings", Icons.Outlined.Settings)
}

@Composable
private fun CopyCloud(link: Link, focused: Boolean) {
    var page by remember { mutableStateOf(Page.Home) }
    var dark by remember { mutableStateOf(false) }
    var sync by remember { mutableStateOf(false) }
    val state by link.state.collectAsState()
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    var last by remember { mutableStateOf<String?>(null) }
    var lastReceived by remember { mutableStateOf<Clip?>(null) }
    val colors = if (dark) darkColorScheme(primary=Lime, onPrimary=Ink, background=Dark, surface=Color(0xFF1D2926), onSurface=Paper, secondary=Lime)
      else lightColorScheme(primary=Forest, onPrimary=Color.White, background=Paper, surface=Color.White, onSurface=Ink, secondary=Forest)
    LaunchedEffect(sync, focused, state.connected) {
        if (!sync || !focused || !state.connected) return@LaunchedEffect
        last = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        while (true) {
            delay(500)
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (text != null && text != last) {
                last = text
                link.send(text)
            }
        }
    }
    LaunchedEffect(state.history.firstOrNull(), focused, sync) {
        val item = state.history.firstOrNull()
        if (sync && focused && item != null && item.received && item !== lastReceived) {
            last = item.text
            lastReceived = item
            clipboard.setPrimaryClip(ClipData.newPlainText("CopyCloud", item.text))
        }
    }
    MaterialTheme(colorScheme=colors) {
        Scaffold(containerColor=colors.background, bottomBar={
            Surface(color=colors.surface, shadowElevation=4.dp, shape=RoundedCornerShape(topStart=26.dp, topEnd=26.dp)) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=12.dp, vertical=12.dp), horizontalArrangement=Arrangement.SpaceEvenly) {
                    Page.entries.forEach { destination ->
                        val selected = destination == page
                        val bg by animateColorAsState(if (selected) Lime else Color.Transparent, label="nav")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).clickable { page=destination }.padding(vertical=5.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                            Box(Modifier.size(48.dp, 34.dp).clip(CircleShape).background(bg), contentAlignment=Alignment.Center) {
                                Icon(destination.icon, null, tint=if(selected) Ink else colors.onSurface)
                            }
                            Text(destination.label, fontSize=11.sp, fontWeight=if(selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }) { padding ->
            AnimatedContent(targetState=page, modifier=Modifier.padding(padding).fillMaxSize(), label="screen") { screen ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=22.dp, vertical=14.dp), verticalArrangement=Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(43.dp).clip(RoundedCornerShape(15.dp)).background(Forest), contentAlignment=Alignment.Center) {
                            Icon(Icons.Outlined.Cloud, null, tint=Lime)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("copycloud.", fontSize=24.sp, fontWeight=FontWeight.ExtraBold, letterSpacing=(-1.3).sp)
                        Spacer(Modifier.weight(1f))
                        Surface(shape=CircleShape, color=colors.surface) {
                            Row(Modifier.padding(horizontal=12.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(if(state.connected) Color(0xFF40AE74) else Color(0xFF8B9792),CircleShape))
                                Spacer(Modifier.width(7.dp))
                                Text(if(state.connected) "Online" else "Offline", fontSize=11.sp, fontWeight=FontWeight.SemiBold)
                            }
                        }
                    }
                    when(screen) {
                        Page.Home -> Overview(state, {page=Page.Connect}, {page=Page.Clipboard})
                        Page.Connect -> Connect(state, link)
                        Page.Clipboard -> ClipboardScreen(state, link, clipboard, { last=it }, { page=Page.Connect })
                        Page.Settings -> {
                            Heading("Make it yours.", "Privacy & preferences")
                            Panel {
                                Text("Appearance", fontSize=20.sp, fontWeight=FontWeight.Bold)
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text("Dark theme", Modifier.weight(1f)); Switch(dark,{dark=it})
                                }
                            }
                            Panel {
                                Text("Clipboard controls", fontSize=20.sp, fontWeight=FontWeight.Bold)
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Live clipboard",fontWeight=FontWeight.SemiBold)
                                        Text("Only while the app is focused. Off by default.",fontSize=12.sp)
                                    }
                                    Switch(sync,{sync=it})
                                }
                                OutlinedButton(onClick={link.clearHistory()},modifier=Modifier.fillMaxWidth()) { Text("Clear session history") }
                            }
                            Panel {
                                Text("Your data, your devices.",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("Text is encrypted in transit. Invitations are shared secrets. Keep them private. Restart the desktop relay to rotate the invite and disconnect the group.",fontSize=14.sp)
                                Text("CopyCloud 0.1.0 • Development alpha",fontSize=12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable private fun Heading(title:String, subtitle:String) {
    Column {
        Text(subtitle.uppercase(),fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.65f))
        Spacer(Modifier.height(6.dp))
        Text(title,fontSize=38.sp,lineHeight=40.sp,fontWeight=FontWeight.ExtraBold,letterSpacing=(-2).sp)
    }
}
@Composable private fun Panel(content:@Composable ColumnScope.()->Unit) {
    Surface(shape=corners,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}
@Composable private fun Overview(state:LinkState, connect:()->Unit, clips:()->Unit) {
    Heading("Everything,\nconnected.", "Your personal device space")
    val progress by animateFloatAsState(if(state.connected) 1f else 0f, animationSpec=tween(650),label="connection")
    Box(Modifier.fillMaxWidth().clip(corners).background(Brush.linearGradient(listOf(Ink, Forest))).padding(22.dp)) {
        Canvas(Modifier.align(Alignment.TopEnd).size(180.dp)) {
            val center=Offset(size.width*.65f,size.height*.45f)
            for(i in 1..4) drawCircle(Lime.copy(alpha=.07f+progress*.05f), radius=size.width*i/6f,center=center,style=androidx.compose.ui.graphics.drawscope.Stroke(width=1.5f))
            drawCircle(Lime.copy(alpha=.3f+progress*.7f),radius=5.dp.toPx(),center=center)
        }
        Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("PRIVATE NETWORK", color=Lime, fontSize=11.sp, letterSpacing=2.sp, fontWeight=FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Text(if(state.connected) "You're linked." else "Your world.\nOne connection.",color=Color.White,fontSize=30.sp,lineHeight=32.sp,fontWeight=FontWeight.ExtraBold)
            Text(if(state.connected) "${state.peers} devices in your group" else "Connect a desktop to get started.",color=Color.White.copy(alpha=.75f),fontSize=13.sp)
            Button(onClick=connect,colors=ButtonDefaults.buttonColors(containerColor=Lime,contentColor=Ink)) {
                Text(if(state.connected) "Manage connection" else "Connect a device",fontWeight=FontWeight.Bold)
                Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward,null,Modifier.size(18.dp))
            }
        }
    }
    Text("Your workspace",fontSize=22.sp,fontWeight=FontWeight.Bold,letterSpacing=(-.5).sp)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Feature("Clipboard","Send text",Icons.Outlined.ContentCopy,Modifier.weight(1f),clips)
        Feature("Devices",if(state.connected) "${state.peers} linked" else "Pair securely",Icons.Outlined.Devices,Modifier.weight(1f),connect)
    }
    Panel {
        Text("On the roadmap",fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("Audio relay · Microphone · File transfer · Remote controls",fontSize=14.sp)
        Text("These tools are not available in this alpha.",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.65f))
    }
}
@Composable private fun Feature(title:String,subtitle:String,icon:ImageVector,modifier:Modifier,action:()->Unit) {
    Surface(modifier.clip(corners).clickable(onClick=action),shape=corners,color=MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(17.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(42.dp).background(Lime,CircleShape),contentAlignment=Alignment.Center) { Icon(icon,null,tint=Ink) }
            Spacer(Modifier.height(8.dp))
            Text(title,fontSize=19.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable private fun Connect(state:LinkState,link:Link) {
    var invite by remember {mutableStateOf("")}
    var visible by remember {mutableStateOf(false)}
    Heading("Link up.", "Secure device connection")
    Panel {
        Icon(Icons.Outlined.Devices,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
        Text(state.status,fontSize=24.sp,fontWeight=FontWeight.Bold)
        Text(if(state.connected) "${state.peers} devices connected including this phone." else "Open the desktop companion on the same Wi-Fi and paste its private invite.",fontSize=14.sp)
        if(!state.connected) {
            OutlinedTextField(value=invite,onValueChange={invite=it},label={Text("Private group invite")},placeholder={Text("CC1|…")},visualTransformation=if(visible) VisualTransformation.None else PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true,trailingIcon={IconButton(onClick={visible=!visible}){Icon(if(visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,"Toggle invite visibility")}})
            Button(onClick={link.connect(invite); invite=""},enabled=invite.isNotBlank()&&!state.busy,modifier=Modifier.fillMaxWidth().height(52.dp)) { Text(if(state.busy) "Connecting…" else "Join private group") }
            if(state.busy) OutlinedButton(onClick={link.disconnect()},modifier=Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            Button(onClick={link.disconnect()},modifier=Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }
    Panel {
        Text("How it works",fontWeight=FontWeight.Bold,fontSize=20.sp)
        Text("01   Start the Windows desktop companion.\n\n02   Copy its invitation and enter it here.\n\n03   Enable clipboard synchronization on the desktop.",fontSize=14.sp)
    }
}
@Composable private fun ClipboardScreen(state:LinkState,link:Link,clipboard:ClipboardManager,onCopy:(String)->Unit,onConnect:()->Unit) {
    var text by remember {mutableStateOf("")}
    Heading("Copied.\nConnected.", "Universal clipboard")
    if(!state.connected) Panel {
        Text("Connect a device first.",fontWeight=FontWeight.Bold)
        Button(onClick=onConnect) {Text("Connect")}
    }
    Panel {
        Text("Send text",fontSize=22.sp,fontWeight=FontWeight.Bold)
        OutlinedTextField(value=text,onValueChange={text=it},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=6,placeholder={Text("Paste or type something…")})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={text=clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()},modifier=Modifier.weight(1f)) { Text("Paste") }
            Button(onClick={link.send(text);text=""},enabled=state.connected&&text.isNotBlank()&&text.toByteArray().size<=60_000,modifier=Modifier.weight(1f)) {Text("Send")}
        }
    }
    Text("Session history",fontSize=22.sp,fontWeight=FontWeight.Bold)
    if(state.history.isEmpty()) Panel { Text("Nothing shared yet. Sent and received text will appear here.") }
    state.history.forEach { item ->
        Panel {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(if(item.received) "From ${item.source}" else "Sent from this phone",fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                IconButton(onClick={onCopy(item.text);clipboard.setPrimaryClip(ClipData.newPlainText("CopyCloud",item.text))}) {Icon(Icons.Outlined.ContentCopy,"Copy text")}
            }
            Text(item.text,maxLines=5,overflow=TextOverflow.Ellipsis)
        }
    }
}
