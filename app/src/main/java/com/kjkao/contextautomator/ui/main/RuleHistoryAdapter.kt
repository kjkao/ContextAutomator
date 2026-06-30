package com.kjkao.contextautomator.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kjkao.contextautomator.R
import com.kjkao.contextautomator.data.local.RuleExecutionHistoryEntity
import com.google.android.material.chip.Chip
import java.text.DateFormat
import java.util.Date

class RuleHistoryAdapter(
    private val formatActionSummary: (String, Int) -> String
) : RecyclerView.Adapter<RuleHistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<RuleExecutionHistoryEntity>()
    private val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    fun submitList(data: List<RuleExecutionHistoryEntity>) {
        items.clear()
        items.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_rule_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position], formatter, formatActionSummary)
    }

    override fun getItemCount(): Int = items.size

    class HistoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val executedAtText: TextView = itemView.findViewById(R.id.executedAtText)
        private val ruleIdChip: Chip = itemView.findViewById(R.id.ruleIdChip)
        private val triggerSummaryText: TextView = itemView.findViewById(R.id.triggerSummaryText)
        private val actionSummaryText: TextView = itemView.findViewById(R.id.actionSummaryText)

        fun bind(
            entry: RuleExecutionHistoryEntity,
            formatter: DateFormat,
            formatActionSummary: (String, Int) -> String
        ) {
            val context = itemView.context
            executedAtText.text = formatter.format(Date(entry.executedAt))
            ruleIdChip.text = context.getString(R.string.rule_history_rule_id, entry.ruleId)
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
