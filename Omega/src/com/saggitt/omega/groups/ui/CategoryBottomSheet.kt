/*
 * This file is part of Neo Launcher
 * Copyright (c) 2022   Neo Launcher Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.saggitt.omega.groups.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.saggitt.omega.compose.components.ComposeSwitchView
import com.saggitt.omega.compose.components.NavigationPreference
import com.saggitt.omega.groups.AppGroups.Companion.KEY_HIDE_FROM_ALL_APPS
import com.saggitt.omega.groups.AppGroups.Group.BooleanCustomization
import com.saggitt.omega.groups.AppGroupsManager.CategorizationType

@Composable
fun CategoryBottomSheet(category: CategorizationType) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Divider(
            modifier = Modifier
                .width(48.dp)
                .height(2.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (category == CategorizationType.Tabs || category == CategorizationType.Flowerpot) {
            TabsBottomSheet()
        } else if (category == CategorizationType.Folders) {
            FoldersBottomSheet()
        }
    }
}

@Composable
fun TabsBottomSheet() {
    val context = LocalContext.current
    val prefs = Utilities.getOmegaPrefs(context)

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Text(
            text = stringResource(id = R.string.default_tab_name),
            modifier = Modifier.fillMaxWidth(),
            color = Color(prefs.themeAccentColor.onGetValue()),
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        CategoryTabItem(
            titleId = R.string.tab_type_smart,
            summaryId = R.string.pref_appcategorization_flowerpot_summary,
            modifier = Modifier.height(72.dp),
            iconId = R.drawable.ic_category,
            onClick = {

            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        CategoryTabItem(
            titleId = R.string.custom,
            summaryId = R.string.tab_type_custom_desc,
            modifier = Modifier.height(72.dp),
            iconId = R.drawable.ic_squares_four,
            onClick = {}
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FoldersBottomSheet() {
    val context = LocalContext.current
    val prefs = Utilities.getOmegaPrefs(context)
    val keyboardController = LocalSoftwareKeyboardController.current
    val hideApps = BooleanCustomization(KEY_HIDE_FROM_ALL_APPS, true)
    var title by remember { mutableStateOf("") }
    val isHidden = remember {
        mutableStateOf(hideApps.value())
    }

    val manager = prefs.drawerAppGroupsManager

    Column(
        modifier = Modifier
            .fillMaxWidth()
    ) {

        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier
                .fillMaxWidth(),
            singleLine = true,
            colors = TextFieldDefaults.outlinedTextFieldColors(
                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12F),
                textColor = MaterialTheme.colorScheme.onSurface
            ),
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = {
                keyboardController?.hide()
            }),
            shape = MaterialTheme.shapes.large,
            label = { Text(text = stringResource(id = R.string.folder_name)) },
            isError = title.isEmpty()
        )

        Spacer(modifier = Modifier.height(16.dp))

        ComposeSwitchView(
            title = stringResource(R.string.tab_hide_from_main),
            iconId = R.drawable.tab_hide_from_main,
            isChecked = isHidden.value,
            onCheckedChange = { newValue ->
                isHidden.value = newValue
            },
            horizontalPadding = 4.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        NavigationPreference(
            title = stringResource(id = R.string.tab_manage_apps),
            route = "manage_apps",
            startIcon = R.drawable.ic_apps,
            endIcon = R.drawable.chevron_right,
            horizontalPadding = 4.dp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = { },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
            Spacer(modifier = Modifier.width(16.dp))

            OutlinedButton(
                onClick = {
                    manager.drawerFolders.saveToJson()
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35F),
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.65F)),
            ) {
                Text(text = stringResource(id = R.string.tab_bottom_sheet_save))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

    }
}
