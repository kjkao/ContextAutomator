package com.kjkao.contextautomator.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.kjkao.contextautomator.R
import com.kjkao.contextautomator.data.local.RuleCheckHistoryEntity
import java.text.DateFormat
import java.util.Date

class RuleCheckHistoryAdapter(
    private val formatActionSummary: (String, Int) -> String
) : RecyclerView.Adapter<RuleCheckHistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<RuleCheckHistoryEntity>()
    private val formatter = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    fun submitList(data: List<RuleCheckHistoryEntity>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule_check_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position], formatter, formatActionSummary)
    }

    override fun getItemCount(): Int = items.size

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val checkedAtText: TextView = itemView.findViewById(R.id.checkedAtText)
        private val ruleIdChip: Chip = itemView.findViewById(R.id.ruleIdChip)
        private val resultChip: Chip = itemView.findViewById(R.id.resultChip)
        private val triggerSummaryText: TextView = itemView.findViewById(R.id.triggerSummaryText)
        private val actionSummaryText: TextView = itemView.findViewById(R.id.actionSummaryText)

        fun bind(
            entry: RuleCheckHistoryEntity,
            formatter: DateFormat,
            formatActionSummary: (String, Int) -> String
        ) {
            val context = itemView.context
            checkedAtText.text = formatter.format(Date(entry.checkedAt))
            ruleIdChip.text = context.getString(R.string.rule_history_rule_id, entry.ruleId)
            resultChip.text = if (entry.matched) {
                context.getString(R.string.rule_check_history_result_matched)
            } else {
                context.getString(R.string.rule_check_history_result_not_matched)
            }
            triggerSummaryText.text = context.getString(
                R.string.rule_history_trigger_line,
                entry.triggerType,
                entry.triggerValue
            )
            actionSummaryText.text = context.getString(
                R.string.rule_history_action_line,
                formatActionSummary(entry.actionType, entry.actionValue)
            )
        }
    }
}