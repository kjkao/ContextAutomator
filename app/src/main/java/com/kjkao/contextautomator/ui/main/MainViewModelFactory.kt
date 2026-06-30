package com.kjkao.contextautomator.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kjkao.contextautomator.alarm.TimeRuleAlarmScheduler
import com.kjkao.contextautomator.data.repo.RuleRepository

class MainViewModelFactory(
    private val repository: RuleRepository,
    private val timeRuleAlarmScheduler: TimeRuleAlarmScheduler
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repository, timeRuleAlarmScheduler) as T
    }
}

