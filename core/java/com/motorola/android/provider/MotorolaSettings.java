/*
 * Copyright (C) 2011 Motorola Mobility LLC
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
 *
 * Code by Hashcode [11-17-2011]
 */

package com.motorola.android.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.net.Uri;
import android.os.SystemProperties;
import android.util.AndroidException;
import android.util.Log;
import com.google.android.collect.Maps;
import java.util.HashMap;
import android.provider.BaseColumns;

public final class MotorolaSettings {
    private static final String TAG = "MotrolaSettings";
    private static final boolean DEBUG = false;

    public static final String AUTHORITY = "com.motorola.android.providers.settings";
    public static final String MOT_PROP_SETTING_VERSION = "sys.mot_settings_secure_version";

    public static final String SYSTEM_CARRIER_PROPERTY = "persist.ril.carrier.numeric";

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHROITY + "/settings");

    public MotorolaSettings() {
    }

    public static class SettingNotFoundException extends AndroidException {
        public SettingNotFoundException(String msg) {
            super(msg);
        }
    }

    /**
     * Common base for tables of name/value settings.
     */
    public static class NameValueTable implements BaseColumns {
        public static final String NAME = "name";
        public static final String VALUE = "value";

        protected static boolean putString(ContentResolver resolver, Uri uri,
                String name, String value) {
            // The database will take care of replacing duplicates.
            try {
                ContentValues values = new ContentValues();
                values.put(NAME, name);
                values.put(VALUE, value);
                resolver.insert(uri, values);
                return true;
            } catch (SQLException e) {
                Log.w(TAG, "Can't set key " + name + " in " + uri, e);
                return false;
            }
        }

        public static Uri getUriFor(Uri uri, String name) {
            return Uri.withAppendedPath(uri, name);
        }
    }

    private static class NameValueCache {
        private final String mVersionSystemProperty;
        private final Uri mUri;

        private static final String[] SELECT_VALUE =
            new String[] { MotorolaSettings.NameValueTable.VALUE };
        private static final String NAME_EQ_PLACEHOLDER = "name=?";

        private final HashMap<String, String> mValues = new HashMap<String, String>();
        private long mValuesVersion = 0;

        // Initially null; set lazily and held forever.  Synchronized on 'this'.
        private IContentProvider mContentProvider = null;

        NameValueCache(String versionSystemProperty, Uri uri) {
            mValues = Maps.newHashMap();
            mValuesVersion = 0L;
            mVersionSystemProperty = versionSystemProperty;
            mUri = uri;
        }

        public String getString(ContentResolver cr, String name) {
            long newValuesVersion = SystemProperties.getLong(mVersionSystemProperty, 0);

            synchronized (this) {
                if (mValuesVersion != newValuesVersion) {
                    if (LOCAL_LOGV) {
                        Log.v(TAG, "invalidate [" + mUri.getLastPathSegment() + "]: current " +
                                newValuesVersion + " != cached " + mValuesVersion);
                    }

                    mValues.clear();
                    mValuesVersion = newValuesVersion;
                }

                if (mValues.containsKey(name)) {
                    return mValues.get(name);  // Could be null, that's OK -- negative caching
                }
            }

            IContentProvider cp = null;
            synchronized (this) {
                cp = mContentProvider;
                if (cp == null) {
                    cp = mContentProvider = cr.acquireProvider(mUri.getAuthority());
                }
            }

            // Try the fast path first, not using query().  If this
            // fails (alternate Settings provider that doesn't support
            // this interface?) then we fall back to the query/table
            // interface.
            if (mCallCommand != null) {
                try {
                    Bundle b = cp.call(mCallCommand, name, null);
                    if (b != null) {
                        String value = b.getPairValue();
                        synchronized (this) {
                            mValues.put(name, value);
                        }
                        return value;
                    }
                    // If the response Bundle is null, we fall through
                    // to the query interface below.
                } catch (RemoteException e) {
                    // Not supported by the remote side?  Fall through
                    // to query().
                }
            }

            Cursor c = null;
            try {
                c = cp.query(mUri, SELECT_VALUE, NAME_EQ_PLACEHOLDER,
                             new String[]{name}, null);
                if (c == null) {
                    Log.w(TAG, "Can't get key " + name + " from " + mUri);
                    return null;
                }

                String value = c.moveToNext() ? c.getString(0) : null;
                synchronized (this) {
                    mValues.put(name, value);
                }
                if (LOCAL_LOGV) {
                    Log.v(TAG, "cache miss [" + mUri.getLastPathSegment() + "]: " +
                            name + " = " + (value == null ? "(null)" : value));
                }
                return value;
            } catch (RemoteException e) {
                Log.w(TAG, "Can't get key " + name + " from " + mUri, e);
                return null;  // Return null, but don't cache it.
            } finally {
                if (c != null) c.close();
            }
        }
    }

    private static volatile NameValueCache mNameValueCache = null;

    /**
     * @deprecated Method getString is deprecated
     */
    public synchronized static String getString(ContentResolver resolver, String name) {
        if (sNameValueCache == null) {
            sNameValueCache = new NameValueCache(MOT_PROP_SETTING_VERSION, CONTENT_URI);
        }
        return sNameValueCache.getString(resolver, name);
    }

    public static boolean putString(ContentResolver resolver,
            String name, String value) {
        return NameValueTable.putString(resolver, CONTENT_URI, name, value);
    }

    public static Uri getUriFor(String name) {
        return NameValueTable.getUriFor(CONTENT_URI, name);
    }

    public static int getInt(ContentResolver cr, String name)
            throws SettingNotFoundException {
        String v = getString(cr, name);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(name);
        }
    }

    public static int getInt(ContentResolver cr, String name, int def) {
        String v = getString(cr, name);
        try {
            return v != null ? Integer.parseInt(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean putInt(ContentResolver cr, String name, int value) {
        return putString(cr, name, Integer.toString(value));
    }

    public static float getFloat(ContentResolver cr, String name)
            throws SettingNotFoundException {
        String v = getString(cr, name);
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(name);
        }
    }

    public static float getFloat(ContentResolver cr, String name, float def) {
        String v = getString(cr, name);
        try {
            return v != null ? Float.parseFloat(v) : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean putFloat(ContentResolver cr, String name, float value) {
        return putString(cr, name, Float.toString(value));
    }

    public static long getLong(ContentResolver cr, String name)
            throws SettingNotFoundException {
        String valString = getString(cr, name);
        try {
            return Long.parseLong(valString);
        } catch (NumberFormatException e) {
            throw new SettingNotFoundException(name);
        }
    }

    public static long getLong(ContentResolver cr, String name, long def) {
        String valString = getString(cr, name);
        long value;
        try {
            value = valString != null ? Long.parseLong(valString) : def;
        } catch (NumberFormatException e) {
            value = def;
        }
        return value;
    }

    public static boolean putLong(ContentResolver contentresolver, String name, long value) {
        return putString(contentresolver, name, Long.toString(value););
    }

    public static final String ACTIVESYNC_POLICY_ALPHANUMERIC_REQUIRED = "activesync_policy_alphanumeric_required";
    public static final String ACTIVESYNC_POLICY_ENABLED = "activesync_policy_enabled";
    public static final String ACTIVESYNC_POLICY_LOCK_PIN_MIN_CHARS = "activesync_policy_lock_pin_min_chars";
    public static final String ACTIVESYNC_POLICY_MIN_COMPLEX_CHARS = "activesync_policy_min_complex_chars";
    public static final String ACTIVESYNC_POLICY_PIN_TYPE = "activesync_policy_lock_type";
    public static final String ACTIVESYNC_POLICY_UNLOCK_MAX_ATTEMPTS = "activesync_policy_unlock_max_attempts";

    public static final String AGPS_FEATURE_ENABLED = "agps_feature_enabled";
    public static final String ALL_AUTO_DIALPAD_ENABLED = "all_auto_diapad_enabled";
    public static final String ASSISTED_DIALING_STATE = "assisted_dialing_state";

    public static final String AUTO_DIAPAD_CUSTOM_PHONE_NUMBER = "auto_diapad_custom_phone_number";
    public static final String AUTO_SYSTEM_CHECK_ENABLED = "auto_system_check";

    public static final String BACK_GROUND_DATA_BACKUP_BY_DATAMANAGER = "back_ground_data_backup_by_datamanager";
    public static final String BT_Dun_Enabled = "Bluetooth_Dun_Enabled";
    public static final String BT_MFB_ENABLED_WHEN_LOCKED = "bluetooth_mfb_enabled_when_locked";
    public static final String CALLING_33860_ENABLED = "calling_33860_enabled";
    public static final String CALLING_GLOBAL_CONTROLS_ENABLE = "calling_global_controls_enable";
    public static final String CALLING_GSM_AD_ENABLED = "calling_gsm_ad_enabled";
    public static final String CALL_AUTO_ANSWER = "call_auto_answer";
    public static final String CALL_CONNECT_TONE = "call_connect_tone";
    public static final String CONTACT_PROTECTION_ENABLED = "contact_protection";
    public static final String COUNTRY_CODE = "country_code";
    public static final String CUR_COUNTRY_AREA_CODE = "cur_country_area_code";
    public static final String CUR_COUNTRY_CODE = "cur_country_code";
    public static final String CUR_COUNTRY_IDD = "cur_country_idd";
    public static final String CUR_COUNTRY_MCC = "cur_country_mcc";
    public static final String CUR_COUNTRY_MDN_LEN = "cur_country_mdn_len";
    public static final String CUR_COUNTRY_NAME = "cur_country_name";
    public static final String CUR_COUNTRY_NDD = "cur_country_ndd";
    public static final String CUR_COUNTRY_UPDATED_BY_USER = "cur_country_updated_by_user";
    public static final String CUSTOM_AUTO_DIALPAD_ENABLED = "custom_auto_diapad_enabled";
    public static final String DATA_NETWORK_SETTING_ENABLED = "data_network_setting_enabled";
    public static final String DEVICE_KEYBOARD_FEATURE_ENABLED = "device_keyboard_feature_enabled";
    public static final String DIALUP_MODEM_RESTRICTION = "dialup_modem_restriction";
    public static final String DOUBLE_TAP = "double_tap";
    public static final String DOWNLOAD_WALLPAPER = "enable_download_wallpaper";
    public static final String ENABLE_TEXT_MSG_REPLY = "qsms_enable_text_message_reply";
    public static final String ENTITLEMENT_CHECK = "entitlement_check";
    public static final String ERI_ALERT_SOUNDS = "eri_alert_sounds";
    public static final String ERI_TEXT_BANNER = "eri_text_banner";

    public static final String ETHERNET_HTTP_PROXY = "ethernet_http_proxy";
    public static final String ETHERNET_HTTP_PROXY_EXCEPTION = "ethernet_http_proxy_exception";
    public static final String ETHERNET_HTTP_PROXY_PORT = "ethernet_http_proxy_port";
    public static final String ETHERNET_HTTP_PROXY_TOGGLE = "ethernet_http_proxy_toggle";
    public static final String ETHERNET_STATIC_DNS1 = "ethernet_static_dns1";
    public static final String ETHERNET_STATIC_DNS2 = "ethernet_static_dns2";
    public static final String ETHERNET_STATIC_GATEWAY = "ethernet_static_gateway";
    public static final String ETHERNET_STATIC_IP = "ethernet_static_ip";
    public static final String ETHERNET_STATIC_NETMASK = "ethernet_static_netmask";
    public static final String ETHERNET_USE_STATIC_IP = "ethernet_use_static_ip";

    public static final String FID_33463_ENABLED = "fid_33463_enabled";
    public static final String FID_34387_MULTIMODE = "fid_34387_multimode";
    public static final String FTR_FULL_CHARGE_NOTIFICATION_ENABLE = "ftr_full_charge_notification_enable";
    public static final String FULL_CHARGE_NOTIFICATION_ENABLE = "full_charge_notification_enable";
    public static final String GEO_TAGGING_FEATURE_ENABLED = "geo_tagging_feature_enabled";
    public static final String HOTSPOT_IDLE_TIMEOUT = "hotspot_idle_timeout";
    public static final String ICE_CONTACTS_ENABLED = "ice_contacts_enabled";
    public static final String INCOMING_CALL_RESTRICTION = "incoming_call_restriction";
    public static final String INCOMING_MESSAGE_RESTRICTION = "incoming_message_restriction";
    public static final String IN_POCKET_DETECTION = "in_pocket_detection";
    public static final String IS_TALKBACK_ON = "is_talkback_on";
    public static final String KEYBOARD_LAYOUT_EXTERNAL = "keyboard_layout_external";
    public static final String KEYGUARD_NEED_FACTORY_RESET_AFTER_POWER_UP = "keyguard_need_factory_reset_after_power_up";
    public static final String LOCATION_RESTRICTION = "location_restriction";

    public static final String LOCK_PIN_CURRENT_FAILED_ATTEMPTS = "lock_pin_current_failed_attempts";
    public static final String LOCK_PIN_MAX_ATTEMPTS = "lock_pin_max_attempts";
    public static final String LOCK_PIN_MIN_CHARS = "lock_pin_min_chars";
    public static final String LOCK_PIN_SETUP = "lock_pin_setup";

    public static final String LOCK_TIMER = "lock_timer";
    public static final String LOCK_TIMER_MAX = "lock_timer_max";

    public static final String LOCK_TYPE = "lock_type";

    public static final String MMS_ACCEPT_CHARSET = "mms_accept_charset";
    public static final String MMS_AUTO_RETRY_ATTEMPTS = "mms_auto_retry_attempts";
    public static final String MMS_AUTO_RETRY_INTERVAL = "mms_auto_retry_interval";
    public static final String MMS_COUNTRY_CODE = "mms_country_code";
    public static final String MMS_HTTP_LINE1KEY = "mms_http_line1key";
    public static final String MMS_HTTP_PARAMETERS = "mms_http_parameters";
    public static final String MMS_MANUAL_RETRY_ENABLE = "mms_manual_retry_enable";
    public static final String MMS_MAXIMUM_MESSAGE_SIZE = "mms_maximum_message_size";
    public static final String MMS_MAX_IMAGE_HEIGHT = "mms_max_image_height";
    public static final String MMS_MAX_IMAGE_WIDTH = "mms_max_image_width";
    public static final String MMS_MML_SERVER = "mms_mml_server";
    public static final String MMS_NOTIFY_WAP_MMSC_ENABLE = "mms_notify_wap_mmsc_enable";
    public static final String MMS_NUMBER_PLUS_PREFIX_ENABLED = "mms_number_plus_prefix_enabled";
    public static final String MMS_ONLINE_ALBUM = "mms_online_album";
    public static final String MMS_ON_REPLY_ALL = "mms_on_reply_all";
    public static final String MMS_REPLY_ALL_ENABLE = "mms_reply_all_enable";
    public static final String MMS_SECONDARY_MMSC_SUPPORT = "mms_secondary_mmsc_support";
    public static final String MMS_TRANSID_ENABLE = "mms_transid_enable";
    public static final String MMS_UAPROF_TAGNAME = "mms_uaprof_tagname";
    public static final String MMS_USER_AGENT = "mms_user_agent";
    public static final String MMS_WAP_REJECT_ENABLE = "mms_wap_reject_enable";
    public static final String MMS_X_WAP_PROFILE_URL = "mms_x_wap_profile_url";
    public static final String NEED_LOCK_DEFAULT_APN = "need_lock_default_apn";
    public static final String NETWORK_LOST_TONE = "network_lost_tone";
    public static final String NEXT_ALARM_UTC = "next_alarm_utc";
    public static final String NOTIFICATION_LED_ENABLED = "notification_led_enabled";
    public static final String NOTIFICATION_LED_MISSEDCALL_ENABLED = "notification_led_missedcall_enabled";
    public static final String NOTIFICATION_LED_VOICEMAIL_ENABLED = "notification_led_voicemail_enabled";
    public static final String OUTGOING_CALL_RESTRICTION = "outgoing_call_restriction";
    public static final String OUTGOING_MESSAGE_RESTRICTION = "outgoing_message_restriction";
    public static final String PGM_CARRIER_STRING = "pgm_carrier_string";
    public static final String POINTER_SPEED_LEVEL = "pointer_speed_level";
    public static final String POWERUP_TONE_ENABLE = "powerup_tone_enable";
    public static final String REF_COUNTRY_AREA_CODE = "ref_country_area_code";
    public static final String REF_COUNTRY_CODE = "ref_country_code";
    public static final String REF_COUNTRY_IDD = "ref_country_idd";
    public static final String REF_COUNTRY_MCC = "ref_country_mcc";
    public static final String REF_COUNTRY_MDN_LEN = "ref_country_mdn_len";
    public static final String REF_COUNTRY_NAME = "ref_country_name";
    public static final String REF_COUNTRY_NDD = "ref_country_ndd";
    public static final String REF_COUNTRY_UPDATED_BY_USER = "ref_country_updated_by_user";
    public static final String RESTRICTION_LOCK = "restriction_lock";
    public static final String RINGTONE_NETWORK_HOME = "ringtone_network_home";
    public static final String RINGTONE_NETWORK_ROAMING = "ringtone_network_roaming";
    public static final String SCREEN_LOCK_ENABLED = "screen_lock";
    public static final String SCREEN_OFF_TIMEOUT_MAX = "screen_off_timeout_max";
    public static final String SETTING_CHECK_CFU_POWERON = "check_cfu_poweron";
    public static final String SETTING_DEBLUR_34412 = "settings_deblur_enable";
    public static final String SETTING_FTR_33859_ENABLED = "sim_33859_isenabled";
    public static final String SETTING_FTR_BUA_ENABLED = "bua_isenabled";
    public static final String SETTING_FTR_CONTACT_ADDMENU_ICON = "contact_additional_menu_icon";
    public static final String SETTING_FTR_CONTACT_ADDMENU_INTENET = "contact_additional_menu_intent";
    public static final String SETTING_FTR_CONTACT_ADDMENU_INTENT_TYPE = "contact_additional_menu_intent_type";
    public static final String SETTING_FTR_CONTACT_ADDMENU_ORDER = "contact_additional_menu_order";
    public static final String SETTING_FTR_CONTACT_ADDMENU_RES_PACKAGE = "contact_additional_menu_res_package";
    public static final String SETTING_FTR_CONTACT_ADDMENU_TITLE = "contact_additional_menu_title";
    public static final String SETTING_FTR_DEBLUR_CONTACT = "deblur_isenabled";
    public static final String SETTING_FTR_DEFAULT_CONTACT_ACCOUNT_NAME = "default_contact_account_name";
    public static final String SETTING_FTR_DEFAULT_CONTACT_ACCOUNT_TYPE = "default_contact_account_type";
    public static final String SETTING_FTR_DISPLAY_SIM_ID_ENABLE = "settings_display_sim_id_enable";
    public static final String SETTING_FTR_DUN_NAT_ENABLED = "dun_nat_enabled";
    public static final String SETTING_FTR_ETHERNET_ENABLED = "ethernet_enabled";
    public static final String SETTING_FTR_ICE_ENABLED = "ice_isenabled";
    public static final String SETTING_FTR_MULTIPLEPDP_ENABLED = "multiple_pdp_isenabled";
    public static final String SETTING_FTR_PRELOAD_CONTACTS = "preload_carrier";
    public static final String SETTING_FTR_RINGER_SWITCH_ENABLE = "ftr_ringer_switch_enable";
    public static final String SETTING_FTR_SIM_NETWORK_LOCK_ENABLE = "settings_sim_network_lock_enable";
    public static final String SETTING_FTR_SIP_ADDRESS_ENABLED = "sip_address_enabled";
    public static final String SETTING_FTR_TETHER_REVERSE_NAT = "tether_reverse_nat_enabled";
    public static final String SHARE_PIC_LOC_ENABLED = "share_pic_loc_enabled";
    public static final String SHOWN_GEO_TRANSIENT_MSG = "shown_geo_transient_msg";
    public static final String SMS_CHARS_REMAIN_TIL_COUNTER = "sms_chars_remaining_before_counter_shown";
    public static final String SMS_FORCE_7BIT_ENCODING = "sms_force_7bit_encoding";
    public static final String SMS_MMS_CALLBACK_NUM_ENABLE = "sms_mms_callback_number_enable";
    public static final String SMS_MMS_DELIVERY_REPORT_ENABLE = "sms_mms_delivery_report_enable";
    public static final String SMS_MMS_ENABLE_ALIAS = "sms_mms_enable_alias";
    public static final String SMS_MMS_ERROR_CODES_ENABLE = "sms_mms_error_codes_enable";
    public static final String SMS_MMS_MAX_NUM_RECIPIENTS = "sms_mms_max_num_recipients";
    public static final String SMS_MMS_MAX_NUM_RECIPIENTS_AVAILABILITY = "sms_mms_max_num_recipients_availability";
    public static final String SMS_MMS_MO_MEMORY_LOW_ENABLE = "sms_mms_mo_memory_low_enable";
    public static final String SMS_MMS_MSG_DETAILS_ENABLE = "sms_mms_message_details_enable";
    public static final String SMS_MMS_MT_MEMORY_LOW_ENABLE = "sms_mms_mt_memory_low_enable";
    public static final String SMS_MMS_SIGNATURE_ENABLE = "sms_mms_signature_enable";
    public static final String SMS_MMS_THRESHOLD = "sms_mms_threshold";
    public static final String SMS_OUTGOING_CHECK_INTERVAL_MS = "sms_outgoing_check_interval_ms";
    public static final String SMS_OUTGOING_CHECK_MAX_COUNT = "sms_outgoing_check_max_count";
    public static final String SMS_PREF_KEY_EMAIL_GATEWAY_NUM = "sms_pref_key_emailgateway_num";
    public static final String SMS_PREF_KEY_TO_EMAIL = "sms_pref_key_to_email";
    public static final String SMS_PRIORITY_ENABLE = "sms_priority_enable";
    public static final String SMS_TO_MMS_AUTO_CONVERT = "sms_to_mms_auto_convert";
    public static final String SMS_TO_MMS_CONVERT_ENABLED = "sms_to_mms_convert_enabled";
    public static final String SOFTWARE_UPDATE_ALERT_ENABLED = "software_update_alert";
    public static final String TTS_CALLER_ID_READOUT = "tts_caller_id_readout";
    public static final String USB_ENTITLEMENT_CHECK = "usb_entitlement_check";
    public static final String USER_NEED_ACCEPT_MOTO_AGREEMENT = "user_need_accept_moto_agreement";
    public static final String VIEWSERVER_IN_SECUREBUILD_ENABLED = "viewserver_in_securebuild_enabled";
    public static final String VM_NUMBER_CDMA = "vm_number_cdma";
    public static final String VM_TF_PP_AUTO_DIALPAD_ENABLED = "vm_tf_auto_dialpad_enabled";
    public static final String VM_VVM_ROAMING_SELECTION = "vm_vvm_roaming_selection";
    public static final String VM_VVM_SELECTION = "vm_vvm_selection";
    public static final String VOLUME_MONITOR_INTERVAL = "volume_monitor_interval";
    public static final String VOLUME_THRESHOLD_PERCENTAGE = "volume_threshold_precentage";
    public static final String WIFI_ADHOC_CHANNEL_NUMBER = "wifi_adhoc_channel_number";
    public static final String WIFI_AP_DHCP_END_ADDR = "wifi_ap_dhcp_end_addr";
    public static final String WIFI_AP_DHCP_START_ADDR = "wifi_ap_dhcp_start_addr";
    public static final String WIFI_AP_DNS1 = "wifi_ap_dns1";
    public static final String WIFI_AP_DNS2 = "wifi_ap_dns2";
    public static final String WIFI_AP_FREQUENCY = "wifi_ap_frequency";
    public static final String WIFI_AP_GATEWAY = "wifi_ap_gateway";
    public static final String WIFI_AP_HIDDEN = "wifi_ap_hidden";
    public static final String WIFI_AP_MAX_SCB = "wifi_ap_max_scb";
    public static final String WIFI_AP_NETMASK = "wifi_ap_netmask";
    public static final String WIFI_DISABLED_BY_ECM = "wifi_disabled_by_ecm";
    public static final String WIFI_HOTSPOT_AUTOCONNECT_ON = "wifi_hotspot_autoconnect";
    public static final String WIFI_HOTSPOT_MASK_SSID = "wifi_hotspot_mask_ssid";
    public static final String WIFI_HOTSPOT_NOTIFY_ON = "wifi_hotspot_notify";
    public static final String WIFI_HOTSPOT_SSID_1 = "wifi_hotspot_ssid_1";
    public static final String WIFI_HOTSPOT_SSID_2 = "wifi_hotspot_ssid_2";
    public static final String WIFI_HOTSPOT_SSID_3 = "wifi_hotspot_ssid_3";
    public static final String WIFI_PROXY = "wifi_proxy";
    public static final String WIFI_PROXY_EXCEPTIONS = "wifi_proxy_exceptions";
    public static final String WIFI_USE_AUTO_IP = "wifi_use_auto_ip";
}
