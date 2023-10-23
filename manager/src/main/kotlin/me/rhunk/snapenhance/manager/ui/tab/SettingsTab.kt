package me.rhunk.snapenhance.manager.ui.tab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import me.rhunk.snapenhance.manager.ui.Tab

class SettingsTab : Tab("settings", isPrimary = true, icon = Icons.Default.Settings) {
    @Composable
    private fun ConfigEditRow(getValue: () -> String?, setValue: (String) -> Unit, label: String) {
        var showDialog by remember { mutableStateOf(false) }

        if (showDialog) {
            val focusRequester = remember { FocusRequester() }

            Dialog(onDismissRequest = {
                showDialog = false
            }) {
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(text = label)
                        var textFieldValue by remember { mutableStateOf((getValue() ?: "").let {
                            TextFieldValue(it, TextRange(it.length))
                        }) }

                        TextField(
                            value = textFieldValue,
                            onValueChange = {
                                textFieldValue = it
                            },
                            modifier = Modifier
                                .focusRequester(focusRequester)
                                .onGloballyPositioned {
                                    focusRequester.requestFocus()
                                }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            Button(onClick = {
                                showDialog = false
                            }) {
                                Text(text = "Cancel")
                            }
                            Button(onClick = {
                                setValue(textFieldValue.text)
                                showDialog = false
                            }) {
                                Text(text = "Save")
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    showDialog = true
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = label, fontSize = 16.sp)
                Text(text = getValue() ?: "(Not specified)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.padding(16.dp))
        }
    }

    @Composable
    override fun Content() {
        Column {
            Spacer(modifier = Modifier.height(16.dp))
            ConfigEditRow(
                getValue = { sharedConfig.snapchatPackageName },
                setValue = { sharedConfig.snapchatPackageName = it },
                label = "Snapchat package name"
            )
            ConfigEditRow(
                getValue = { sharedConfig.snapEnhancePackageName },
                setValue = { sharedConfig.snapEnhancePackageName = it },
                label = "SnapEnhance package name"
            )
        }
    }
}