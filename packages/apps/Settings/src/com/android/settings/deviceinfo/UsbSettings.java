/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.deviceinfo;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ContentQueryMap;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.util.Log;

import com.android.settings.R;
import com.android.settings.SettingsPreferenceFragment;
import com.android.settings.Utils;

/**
 * USB storage settings.
 */
public class UsbSettings extends SettingsPreferenceFragment {

    private static final String TAG = "UsbSettings";

    private static final String KEY_MASS_STORAGE = "usb_mass_storage";

    private UsbManager mUsbManager;
    private StorageManager storageManager;
    private StorageVolume[] storageVolumes;
    private CheckBoxPreference mUms;

    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        public void onReceive(Context content, Intent intent) {
            updateToggles(mUsbManager.getDefaultFunction());
        }
    };

    private PreferenceScreen createPreferenceHierarchy() {
        PreferenceScreen root = getPreferenceScreen();
        if (root != null) {
            root.removeAll();
        }
        addPreferencesFromResource(R.xml.usb_settings);
        root = getPreferenceScreen();

        mUms = (CheckBoxPreference)root.findPreference(KEY_MASS_STORAGE);
        if (!storageVolumes[0].allowMassStorage()) {
            root.removePreference(mUms);
        }

        return root;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        storageManager = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        storageVolumes = storageManager.getVolumeList();
    }

    @Override
    public void onPause() {
        super.onPause();
        getActivity().unregisterReceiver(mStateReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();

        // Make sure we reload the preference hierarchy since some of these settings
        // depend on others...
        createPreferenceHierarchy();

        // ACTION_USB_STATE is sticky so this will call updateToggles
        getActivity().registerReceiver(mStateReceiver,
                new IntentFilter(UsbManager.ACTION_USB_STATE));
    }

    private void updateToggles(String function) {
        if (UsbManager.USB_FUNCTION_MASS_STORAGE.equals(function)) {
            mUms.setChecked(true);
        } else  {
            mUms.setChecked(false);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {

        // Don't allow any changes to take effect as the USB host will be disconnected, killing
        // the monkeys
        if (Utils.isMonkeyRunning()) {
            return true;
        }
        // temporary hack - using check boxes as radio buttons
        // don't allow unchecking them
        if (preference instanceof CheckBoxPreference) {
            CheckBoxPreference checkBox = (CheckBoxPreference)preference;
            if (!checkBox.isChecked()) {
                checkBox.setChecked(true);
                return true;
            }
        }
        if (preference == mUms) {
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.USB_MASS_STORAGE_ENABLED, 1);
            mUsbManager.setCurrentFunction(UsbManager.USB_FUNCTION_MASS_STORAGE, true);
            updateToggles(UsbManager.USB_FUNCTION_MASS_STORAGE);
        }

        return true;
    }
}
