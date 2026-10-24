// FILE: ./app/src/main/java/com/asterisk/voip/data/repository/AsteriskRepository.kt
package com.asterisk.voip.data.repository

import com.asterisk.voip.data.database.*
import com.asterisk.voip.data.model.*
import kotlinx.coroutines.flow.Flow

class AsteriskRepository(
    private val extensionDao: ExtensionDao,
    private val dialPlanDao: DialPlanDao,
    private val offlineMessageDao: OfflineMessageDao,
    private val voicePromptDao: VoicePromptDao,
    private val dialPlanRuleDao: DialPlanRuleDao,
    private val dialPlanScheduleDao: DialPlanScheduleDao,
    private val registrationDao: RegistrationDao
) {
    // Extensions
    val allExtensions: Flow<List<Extension>> = extensionDao.getAllExtensions()
    val enabledExtensions: Flow<List<Extension>> = extensionDao.getEnabledExtensions()

    suspend fun getExtensionById(id: Long) = extensionDao.getExtensionById(id)
    suspend fun getExtensionByNumber(number: String) = extensionDao.getExtensionByNumber(number)
    suspend fun insertExtension(extension: Extension) = extensionDao.insertExtension(extension)
    suspend fun updateExtension(extension: Extension) = extensionDao.updateExtension(extension)
    suspend fun deleteExtension(extension: Extension) = extensionDao.deleteExtension(extension)
    suspend fun getExtensionCount() = extensionDao.getExtensionCount()

    // DialPlans (Legacy)
    val allDialPlans: Flow<List<DialPlan>> = dialPlanDao.getAllDialPlans()

    fun getDialPlansByContext(context: String) = dialPlanDao.getDialPlansByContext(context)
    suspend fun insertDialPlan(dialPlan: DialPlan) = dialPlanDao.insertDialPlan(dialPlan)
    suspend fun updateDialPlan(dialPlan: DialPlan) = dialPlanDao.updateDialPlan(dialPlan)
    suspend fun deleteDialPlan(dialPlan: DialPlan) = dialPlanDao.deleteDialPlan(dialPlan)

    // Voice Prompts
    val allVoicePrompts: Flow<List<VoicePrompt>> = voicePromptDao.getAllPrompts()
    val enabledVoicePrompts: Flow<List<VoicePrompt>> = voicePromptDao.getEnabledPrompts()

    suspend fun getVoicePromptById(id: Long) = voicePromptDao.getPromptById(id)
    suspend fun insertVoicePrompt(prompt: VoicePrompt) = voicePromptDao.insertPrompt(prompt)
    suspend fun updateVoicePrompt(prompt: VoicePrompt) = voicePromptDao.updatePrompt(prompt)
    suspend fun deleteVoicePrompt(prompt: VoicePrompt) = voicePromptDao.deletePrompt(prompt)

    // DialPlan Rules
    val allDialPlanRules: Flow<List<DialPlanRule>> = dialPlanRuleDao.getAllRules()
    val enabledDialPlanRules: Flow<List<DialPlanRule>> = dialPlanRuleDao.getEnabledRules()

    suspend fun getDialPlanRuleById(id: Long) = dialPlanRuleDao.getRuleById(id)
    suspend fun getRulesByCondition(condition: DialPlanCondition) = dialPlanRuleDao.getRulesByCondition(condition)
    suspend fun getRulesByPattern(pattern: String) = dialPlanRuleDao.getRulesByPattern(pattern)
    suspend fun insertDialPlanRule(rule: DialPlanRule) = dialPlanRuleDao.insertRule(rule)
    suspend fun updateDialPlanRule(rule: DialPlanRule) = dialPlanRuleDao.updateRule(rule)
    suspend fun deleteDialPlanRule(rule: DialPlanRule) = dialPlanRuleDao.deleteRule(rule)

    // DialPlan Schedules
    val allDialPlanSchedules: Flow<List<DialPlanSchedule>> = dialPlanScheduleDao.getAllSchedules()
    val enabledDialPlanSchedules: Flow<List<DialPlanSchedule>> = dialPlanScheduleDao.getEnabledSchedules()

    suspend fun getDialPlanScheduleById(id: Long) = dialPlanScheduleDao.getScheduleById(id)
    suspend fun insertDialPlanSchedule(schedule: DialPlanSchedule) = dialPlanScheduleDao.insertSchedule(schedule)
    suspend fun updateDialPlanSchedule(schedule: DialPlanSchedule) = dialPlanScheduleDao.updateSchedule(schedule)
    suspend fun deleteDialPlanSchedule(schedule: DialPlanSchedule) = dialPlanScheduleDao.deleteSchedule(schedule)

    // Offline Messages
    suspend fun getPendingMessages() = offlineMessageDao.getPendingMessages()
    suspend fun getPendingMessagesForExtension(extension: String) =
        offlineMessageDao.getPendingMessagesForExtension(extension)
    suspend fun getPendingMessageCount(extension: String) =
        offlineMessageDao.getPendingMessageCount(extension)
    suspend fun insertOfflineMessage(message: OfflineMessage) =
        offlineMessageDao.insertMessage(message)
    suspend fun markMessageAsDelivered(messageId: Long) =
        offlineMessageDao.markAsDelivered(messageId)
    suspend fun deleteExpiredMessages(timestamp: Long) =
        offlineMessageDao.deleteExpiredMessages(timestamp)
    suspend fun deleteDeliveredOlderThan(timestamp: Long) =
        offlineMessageDao.deleteDeliveredOlderThan(timestamp)

    // Registrations (persisted bindings)
    suspend fun getRegistrations() = registrationDao.getAll()
    suspend fun saveRegistration(r: Registration) = registrationDao.upsert(r)
    suspend fun deleteRegistration(extension: String) = registrationDao.delete(extension)
    suspend fun deleteStaleRegistrations(olderThan: Long) = registrationDao.deleteStale(olderThan)
}
