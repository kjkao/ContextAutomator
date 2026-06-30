package com.kjkao.contextautomator.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kjkao.contextautomator.R
import com.kjkao.contextautomator.data.local.RuleEntity
import com.kjkao.contextautomator.databinding.ItemRuleBinding
import com.kjkao.contextautomator.domain.model.ActionType
import com.kjkao.contextautomator.domain.model.TriggerCondition
import com.kjkao.contextautomator.domain.model.TriggerType

class RuleAdapter(
    private val onDeleteClicked: (RuleEntity) -> Unit,
    private val onEditClicked: (RuleEntity) -> Unit,
    private val onEnabledChanged: (RuleEntity, Boolean) -> Unit
) : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {

    private val items = mutableListOf<RuleEntity>()

    fun submitList(rules: List<RuleEntity>) {
        items.clear()
        items.addAll(rules)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class RuleViewHolder(
        private val binding: ItemRuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: RuleEntity) {
            binding.ruleTypeChip.text = triggerTypeLabel(rule)
            binding.ruleStateChip.text = if (rule.enabled) {
                binding.root.context.getString(R.string.rule_enabled_state_on)
            } else {
                binding.root.context.getString(R.string.rule_enabled_state_off)
            }
            binding.ruleSsidText.text = triggerSummary(rule)
            binding.ruleModeText.text = actionSummary(rule)
            binding.ruleEnabledSwitch.setOnCheckedChangeListener(null)
            binding.ruleEnabledSwitch.isChecked = rule.enabled
            binding.ruleEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
                onEnabledChanged(rule, isChecked)
            }
            binding.root.setOnClickListener { onEditClicked(rule) }
            binding.deleteRuleButton.setOnClickListener { onDeleteClicked(rule) }
        }

        private fun triggerTypeLabel(rule: RuleEntity): String {
            return when (rule.triggerType) {
                TriggerType.WIFI_SSID.name -> binding.root.context.getString(R.string.rule_type_wifi)
                TriggerType.TIME_RANGE.name -> binding.root.context.getString(R.string.rule_type_time)
                TriggerType.BLUETOOTH_DEVICE.name -> binding.root.context.getString(R.string.rule_type_bluetooth)
                TriggerType.CHARGING_STATE.name -> binding.root.context.getString(R.string.rule_type_charging)
                TriggerType.GEOFENCE.name -> binding.root.context.getString(R.string.rule_type_geofence)
                TriggerType.APP_FOREGROUND.name -> binding.root.context.getString(R.string.rule_type_app)
                else -> binding.root.context.getString(R.string.trigger_unknown)
            }
        }

        private fun triggerSummary(rule: RuleEntity): String {
            val conditionText = when (rule.triggerType) {
                TriggerType.CHARGING_STATE.name -> {
                    if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                        binding.root.context.getString(R.string.condition_not_charging_short)
                    } else {
                        binding.root.context.getString(R.string.condition_charging_short)
                    }
                }
                TriggerType.GEOFENCE.name -> {
                    if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                        binding.root.context.getString(R.string.condition_outside_short)
                    } else {
                        binding.root.context.getString(R.string.condition_inside_short)
                    }
                }
                TriggerType.APP_FOREGROUND.name -> {
                    if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                        binding.root.context.getString(R.string.condition_not_foreground_short)
                    } else {
                        binding.root.context.getString(R.string.condition_foreground_short)
                    }
                }
                else -> {
                    if (rule.triggerCondition == TriggerCondition.CONNECTED.name) {
                        binding.root.context.getString(R.string.condition_connected_short)
                    } else {
                        binding.root.context.getString(R.string.condition_detected_short)
                    }
                }
            }
            return when (rule.triggerType) {
                TriggerType.WIFI_SSID.name -> binding.root.context.getString(R.string.trigger_wifi_summary, conditionText, rule.triggerValue)
                TriggerType.BLUETOOTH_DEVICE.name -> binding.root.context.getString(R.string.trigger_bluetooth_summary, conditionText, rule.triggerValue)
                TriggerType.TIME_RANGE.name -> binding.root.context.getString(
                    R.string.trigger_time_summary,
                    toTimeText(rule.timeStartMinutes ?: rule.timeEndMinutes)
                )
                TriggerType.CHARGING_STATE.name -> binding.root.context.getString(R.string.trigger_charging_summary, conditionText)
                TriggerType.GEOFENCE.name -> binding.root.context.getString(R.string.trigger_geofence_summary, conditionText, rule.triggerValue)
                TriggerType.APP_FOREGROUND.name -> binding.root.context.getString(R.string.trigger_app_foreground_summary, conditionText, rule.triggerValue)
                else -> binding.root.context.getString(R.string.trigger_unknown)
            }
        }

        private fun actionSummary(rule: RuleEntity): String {
            return when (rule.actionType) {
                ActionType.RINGER_MODE.name -> {
                    when (rule.actionValue) {
                        2 -> binding.root.context.getString(R.string.action_ringer_normal)
                        1 -> binding.root.context.getString(R.string.action_ringer_vibrate)
                        0 -> binding.root.context.getString(R.string.action_ringer_silent)
                        else -> binding.root.context.getString(R.string.action_ringer_raw, rule.actionValue)
                    }
                }
                ActionType.RING_VOLUME.name -> binding.root.context.getString(R.string.action_volume_summary, rule.actionValue)
                ActionType.MEDIA_VOLUME.name -> binding.root.context.getString(R.string.action_media_volume_summary, rule.actionValue)
                ActionType.SCREEN_BRIGHTNESS.name -> binding.root.context.getString(R.string.action_brightness_summary, rule.actionValue)
                ActionType.SCREEN_TIMEOUT.name -> binding.root.context.getString(R.string.action_timeout_summary, rule.actionValue)
                else -> binding.root.context.getString(R.string.action_unknown)
            }
        }

        private fun toTimeText(minuteOfDay: Int?): String {
            if (minuteOfDay == null) return "--:--"
            val hour = minuteOfDay / 60
            val minute = minuteOfDay % 60
            return String.format("%02d:%02d", hour, minute)
        }
    }
}

