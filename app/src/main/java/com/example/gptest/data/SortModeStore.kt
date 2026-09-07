package com.example.gptest.data

import com.example.gptest.business.QuoteSortMode
import com.example.gptest.ui.alert.RapidAlertThresholds

interface SortModeStore {
    var mode: QuoteSortMode
    var intervalSeconds: Long
    var monitorRunning: Boolean
    var rapidAlertThresholds: RapidAlertThresholds
}
