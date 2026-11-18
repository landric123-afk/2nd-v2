package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage
import com.example.auth.signOutFromGoogle
import com.example.data.remote.UiState
import com.example.data.remote.UserProfile
import com.example.ui.theme.AcidGreen
import com.example.ui.theme.BlackCarbon
import com.example.ui.theme.DarkGreyCarbon
import com.example.ui.theme.GuerrillaPink
import com.example.ui.theme.LaserCyan
import com.example.ui.theme.LightGreyCarbon
import com.example.ui.theme.MutedText
import com.example.ui.theme.WhiteText
import com.example.ui.viewmodel.MainViewModel

@Composable
fun ProfileScreen(
    viewModel: MainViewModel,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val profileState by viewModel.userProfileState.collectAsState()
    val profileSaveMessage by viewModel.profileSaveMessage.collectAsState()
    val isSavingProfile by viewModel.isSavingProfile.collectAsState()
    val soundDrops by viewModel.soundDropsList.collectAsState()
    val graffitiList by viewModel.virtualGraffitiList.collectAsState()
    val geofenceStatus by viewModel.geofenceStatus.collectAsState()

    val loadedProfile: UserProfile? = (profileState as? UiState.Success)?.data

    var displayNameInput by remember(loadedProfile?.userId) {
        mutableStateOf(loadedProfile?.displayName ?: "")
    }
    var handleInput by remember(loadedProfile?.userId) {
        mutableStateOf(loadedProfile?.handle ?: "")
    }
    var bioInput by remember(loadedProfile?.userId) {
        mutableStateOf(loadedProfile?.bio ?: "")
    }

    LaunchedEffect(loadedProfile) {
        if (loadedProfile != null) {
            if (displayNameInput.isBlank()) displayNameInput = loadedProfile.displayName
            if (handleInput.isBlank()) handleInput = loadedProfile.handle
            if (bioInput.isBlank()) bioInput = loadedProfile.bio
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .background(BlackCarbon)
            .testTag("profile_screen")
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon),
                border = BorderStroke(1.dp, GuerrillaPink.copy(alpha = 0.45f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp)
                ) {
                    val photoUrl = loadedProfile?.photoUrl
                    if (!photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "Google account profile avatar",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .border(2.dp, GuerrillaPink, CircleShape)
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(LightGreyCarbon)
                                .border(2.dp, GuerrillaPink, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Operator avatar",
                                tint = GuerrillaPink,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = loadedProfile?.displayName?.ifBlank { "2ND OPERATOR" } ?: "LOADING PROFILE...",
                            color = WhiteText,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${loadedProfile?.handle ?: "operator"}",
                            color = AcidGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        if (!loadedProfile?.email.isNullOrBlank()) {
                            Text(
                                text = loadedProfile?.email ?: "",
                                color = MutedText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Quick Stats Row
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                ProfileStatCard(
                    label = "SOUND-DROPS",
                    value = "${soundDrops.size}",
                    accent = AcidGreen,
                    icon = Icons.Default.GraphicEq,
                    modifier = Modifier.weight(1f)
                )
                ProfileStatCard(
                    label = "AR STENCILS",
                    value = "${graffitiList.size}",
                    accent = LaserCyan,
                    icon = Icons.Default.ViewInAr,
                    modifier = Modifier.weight(1f)
                )
                ProfileStatCard(
                    label = "GEOFENCES",
                    value = "${geofenceStatus.registeredCount}",
                    accent = GuerrillaPink,
                    icon = Icons.Default.LocationOn,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Profile Registration & Edit Form
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon),
                border = BorderStroke(1.dp, LaserCyan.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "OPERATOR IDENTITY (FIRESTORE SYNC)",
                        color = LaserCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )

                    if (profileState is UiState.Error) {
                        Text(
                            text = (profileState as UiState.Error).message,
                            color = Color(0xFFFF8A80),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }

                    OutlinedTextField(
                        value = displayNameInput,
                        onValueChange = { displayNameInput = it },
                        label = { Text("DISPLAY NAME") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = LaserCyan,
                            focusedLabelColor = LaserCyan,
                            unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                            unfocusedLabelColor = MutedText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_display_name_input")
                    )

                    OutlinedTextField(
                        value = handleInput,
                        onValueChange = { handleInput = it },
                        label = { Text("CALLSIGN HANDLE (USED ON DROPS)") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AcidGreen,
                            focusedLabelColor = AcidGreen,
                            unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                            unfocusedLabelColor = MutedText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_handle_input")
                    )

                    OutlinedTextField(
                        value = bioInput,
                        onValueChange = { bioInput = it },
                        label = { Text("OPERATOR BIO") },
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GuerrillaPink,
                            focusedLabelColor = GuerrillaPink,
                            unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                            unfocusedLabelColor = MutedText
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("profile_bio_input")
                    )

                    if (!profileSaveMessage.isNullOrBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = AcidGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = profileSaveMessage ?: "",
                                color = AcidGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.saveUserProfile(
                                displayName = displayNameInput,
                                handle = handleInput,
                                bio = bioInput
                            )
                        },
                        enabled = !isSavingProfile && displayNameInput.isNotBlank() && handleInput.trim().length >= 2,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AcidGreen,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_profile_button")
                    ) {
                        if (isSavingProfile) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isSavingProfile) "SYNCING..." else "SAVE PROFILE",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Geofencing & Sign Out Controls
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon),
                border = BorderStroke(1.dp, MutedText.copy(alpha = 0.25f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "GEOFENCING ENGINE: ${geofenceStatus.statusText}",
                        color = AcidGreen,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "FusedLocationProviderClient + Play Services Circular Geofencing monitors Sound-Drop spheres and dispatches local notifications on entry.",
                        color = MutedText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.wipeDatabase() },
                            border = BorderStroke(1.dp, MutedText.copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "RESET SEED",
                                color = WhiteText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.prepareForSignOut()
                                signOutFromGoogle(
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onSignOutComplete = onSignedOut
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GuerrillaPink,
                                contentColor = Color.Black
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "SIGN OUT",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileStatCard(
    label: String,
    value: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                color = WhiteText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
            Text(
                text = label,
                color = MutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                letterSpacing = 0.6.sp,
                maxLines = 1
            )
        }
    }
}
