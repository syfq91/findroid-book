package dev.jdtech.jellyfin.settings.presentation.models

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.settings.domain.models.Preference as PreferenceBackend
import dev.jdtech.jellyfin.settings.presentation.enums.DeviceType

data class PreferenceSwitch(
    @param:StringRes override val nameStringResource: Int,
    @param:StringRes override val descriptionStringRes: Int? = null,
    @param:DrawableRes override val iconDrawableId: Int? = null,
    override val enabled: Boolean = true,
    override val dependencies: List<PreferenceBackend<Boolean>> = emptyList(),
    override val supportedDeviceTypes: List<DeviceType> = listOf(DeviceType.PHONE, DeviceType.TV),
    val onClick: (PreferenceSwitch) -> Unit = {},
    val backendPreference: PreferenceBackend<Boolean>,
    val value: Boolean = false,
    /** When set, only show this switch while [visibleForPreference] has one of [visibleForValues]. */
    val visibleForPreference: PreferenceBackend<String>? = null,
    val visibleForValues: Set<String>? = null,
) : Preference
