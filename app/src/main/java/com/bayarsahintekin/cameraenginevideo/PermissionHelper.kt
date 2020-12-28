/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bayarsahintekin.cameraenginevideo

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

internal object PermissionHelper {
    const val REQUEST_CODE_ASK_PERMISSIONS = 1
    private val PERMISSIONS_ARRAY = arrayOf(
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val permissionsList: MutableList<String> =
        ArrayList(PERMISSIONS_ARRAY.size)

    fun hasPermission(activity: Activity): Boolean {
        for (permission in PERMISSIONS_ARRAY) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    permission
                ) !== PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    fun requestPermission(activity: Activity) {
        for (permission in PERMISSIONS_ARRAY) {
            if (ContextCompat.checkSelfPermission(
                    activity,
                    permission
                ) !== PackageManager.PERMISSION_GRANTED
            ) {
                permissionsList.add(permission)
            }
        }
        ActivityCompat.requestPermissions(
            activity, permissionsList.toTypedArray(),
            REQUEST_CODE_ASK_PERMISSIONS
        )
    }
}