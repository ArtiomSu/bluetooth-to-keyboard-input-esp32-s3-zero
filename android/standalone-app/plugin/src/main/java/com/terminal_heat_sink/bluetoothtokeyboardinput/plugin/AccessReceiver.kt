package com.terminal_heat_sink.bluetoothtokeyboardinput.plugin

import keepass2android.pluginsdk.PluginAccessBroadcastReceiver
import keepass2android.pluginsdk.Strings

/**
 * Handles the three access-negotiation broadcasts from KeePass2Android:
 *   - ACTION_TRIGGER_REQUEST_ACCESS  → plugin sends its required scopes
 *   - ACTION_RECEIVE_ACCESS          → K2A grants an access token; stored by base class
 *   - ACTION_REVOKE_ACCESS           → token revoked; removed by base class
 *
 * The base class [PluginAccessBroadcastReceiver] implements all the token
 * storage logic.  We only need to declare which scope(s) we need.
 */
class AccessReceiver : PluginAccessBroadcastReceiver() {

    /**
     * We need SCOPE_CURRENT_ENTRY so we can add typing actions to any open
     * KeePass entry and read the Username / Password fields when the user
     * triggers one of those actions.
     */
    override fun getScopes(): ArrayList<String> =
        arrayListOf(Strings.SCOPE_CURRENT_ENTRY)
}
