package com.example.gptest.data

import com.example.gptest.ui.alert.AlertCondition
import com.example.gptest.ui.alert.AlertMatchMode
import com.example.gptest.ui.alert.AlertMetric
import com.example.gptest.ui.alert.AlertNotifyMode
import com.example.gptest.ui.alert.AlertOperator
import com.example.gptest.ui.alert.AlertRule
import com.example.gptest.ui.alert.AlertRuleStatus
import com.example.gptest.ui.alert.AlertStockOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertRuleMapperTest {

    @Test
    fun roundTrip_preservesRuleAndCompareStock() {
        val rule = AlertRule(
            id = "r1",
            name = "联动",
            enabled = true,
            matchMode = AlertMatchMode.ANY,
            notifyMode = AlertNotifyMode.ONCE,
            conditions = listOf(
                AlertCondition(
                    id = "c1",
                    stock = AlertStockOption("sz002491", "通鼎互联"),
                    metric = AlertMetric.CHANGE_PERCENT,
                    operator = AlertOperator.GTE,
                    compareStock = AlertStockOption("sh600000", "浦发银行")
                ),
                AlertCondition(
                    id = "c2",
                    stock = AlertStockOption("sz002491", "通鼎互联"),
                    metric = AlertMetric.PRICE,
                    operator = AlertOperator.CROSS_UP,
                    numberValue = "21"
                )
            ),
            status = AlertRuleStatus.FIRED,
            lastTriggeredMs = 1_700_000_000_000L,
            onceConsumed = true
        )
        val entity = AlertRuleMapper.toEntity(rule, sortOrder = 3)
        val restored = AlertRuleMapper.toRule(entity, AlertRuleMapper.toConditionEntities(rule))
        assertEquals(rule, restored)
        assertEquals(3, entity.sortOrder)
    }

    @Test
    fun unknownEnum_fallsBackToDefaults() {
        val entity = AlertRuleEntity(
            id = "r1",
            name = "坏数据",
            enabled = false,
            matchMode = "NOPE",
            notifyMode = "NOPE",
            status = "NOPE",
            lastTriggeredMs = null,
            onceConsumed = false,
            sortOrder = 0
        )
        val condition = AlertConditionEntity(
            id = "c1",
            ruleId = "r1",
            stockCode = "sz002491",
            stockName = "通鼎互联",
            metric = "NOPE",
            operator = "NOPE",
            numberValue = "1",
            compareCode = null,
            compareName = null,
            sortOrder = 0
        )
        val rule = AlertRuleMapper.toRule(entity, listOf(condition))
        assertEquals(AlertMatchMode.ALL, rule.matchMode)
        assertEquals(AlertNotifyMode.COOLDOWN, rule.notifyMode)
        assertEquals(AlertRuleStatus.WAITING, rule.status)
        assertEquals(AlertMetric.PRICE, rule.conditions.single().metric)
        assertEquals(AlertOperator.CROSS_UP, rule.conditions.single().operator)
        assertNull(rule.conditions.single().compareStock)
    }
}
