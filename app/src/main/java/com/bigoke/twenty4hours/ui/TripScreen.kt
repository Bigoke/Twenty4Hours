package com.bigoke.twenty4hours.ui

import android.Manifest
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigoke.twenty4hours.alarm.Trip
import com.bigoke.twenty4hours.alarm.TripStore
import com.bigoke.twenty4hours.alarm.formatDistance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

private fun Address.title(): String =
    featureName?.takeIf { it.isNotBlank() && it.any { c -> c.isLetter() } } ?: thoroughfare ?: locality ?: getAddressLine(0) ?: "Place"

private fun Address.subtitle(): String = (0..maxAddressLineIndex).mapNotNull { getAddressLine(it) }.joinToString(", ")

/** "Wake me before my stop": pick a destination, and the phone rings when you get close. */
@Composable
fun TripScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val trip by TripStore.trip.collectAsState()
    val distance by TripStore.distance.collectAsState()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Address>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Address?>(null) }
    var radius by remember { mutableIntStateOf(800) }
    var message by remember { mutableStateOf<String?>(null) }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res[Manifest.permission.ACCESS_FINE_LOCATION] == true || res[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val a = picked
        if (ok && a != null) {
            TripStore.start(ctx, Trip(a.title(), a.latitude, a.longitude, radius))
            message = null
        } else if (!ok) message = "Location permission is needed to know when you're close."
    }

    LaunchedEffect(query) {
        if (query.trim().length < 3) { results = emptyList(); return@LaunchedEffect }
        delay(450)
        searching = true
        results = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale.getDefault()).getFromLocationName(query.trim(), 8) ?: emptyList()
            }.getOrDefault(emptyList())
        }
        searching = false
    }

    Box(Modifier.fillMaxSize().background(T24.Bg)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().imePadding(),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp).height(56.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = T24.Text)
                    }
                    Text("On the way", color = T24.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Wakes you before your stop — for a nap on the bus, train or a long drive as a passenger.",
                    color = T24.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp)
                )
                Spacer(Modifier.height(18.dp))
            }

            val t = trip
            if (t != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                            .clip(RoundedCornerShape(24.dp)).background(T24.Surface)
                            .border(1.dp, T24.Line, RoundedCornerShape(24.dp)).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Canvas(Modifier.size(150.dp)) {
                            val c = Offset(size.width / 2, size.height / 2)
                            val r = size.minDimension * 0.38f
                            drawFace(c, r, size.minDimension * 0.09f, outerLine = false)
                            drawKnob(c, r * 0.3f, amber = true)
                        }
                        Text(t.name, color = T24.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(
                            distance?.let { formatDistance(it) + " to go" } ?: "Finding your location…",
                            color = T24.AmberLight, fontSize = 30.sp, fontWeight = FontWeight.Light
                        )
                        Text("Rings within ${formatDistance(t.radiusM.toFloat())}", color = T24.Muted, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        PillButton("Stop tracking", Modifier.fillMaxWidth(), height = 50.dp) { TripStore.stop(ctx) }
                    }
                }
                return@LazyColumn
            }

            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it; picked = null }, singleLine = true,
                    label = { Text("Where are you going?") },
                    placeholder = { Text("Station, street or place") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    colors = fieldColors()
                )
                Spacer(Modifier.height(8.dp))
                if (searching) Text("Searching…", color = T24.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
                else if (query.trim().length >= 3 && results.isEmpty() && picked == null) {
                    Text("No places found. Try adding the town name.", color = T24.Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            val p = picked
            if (p == null) {
                items(results) { a ->
                    Column(
                        Modifier.fillMaxWidth().clickable { picked = a }.padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(a.title(), color = T24.Text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.subtitle(), color = T24.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                item {
                    Column(Modifier.padding(horizontal = 24.dp)) {
                        Spacer(Modifier.height(8.dp))
                        Text(p.title(), color = T24.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        Text(p.subtitle(), color = T24.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(18.dp))
                        SectionTitle("Ring when I'm within")
                        ChoiceRow(listOf(300, 500, 800, 1500, 3000), radius, { formatDistance(it.toFloat()) }) { radius = it }
                        Spacer(Modifier.height(22.dp))
                        PillButton("Start", Modifier.fillMaxWidth(), primary = true, height = 56.dp) {
                            val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
                            perms.launch(list.toTypedArray())
                        }
                        message?.let { Text(it, color = T24.Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                        Text(
                            "Keep the screen able to sleep — tracking runs in the background and uses GPS until you arrive or stop it.",
                            color = T24.Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)
                        )
                    }
                }
            }
        }
    }
}
