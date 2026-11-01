package cn.greenhouse.twin

import android.app.Application
import cn.greenhouse.twin.data.repository.HomeStateRepository
import cn.greenhouse.twin.data.monitoring.MonitorRepository

class GreenhouseApplication : Application() {
    // 同步服务由应用持有，所有页面和 Activity 重建共享同一个实例。
    val homeRepository: HomeStateRepository by lazy { HomeStateRepository(this) }
    val monitorRepository: MonitorRepository by lazy {
        MonitorRepository(this, homeRepository.state, homeRepository.connectionStore,
            homeRepository::trustedNowUtcMs)
    }
}
