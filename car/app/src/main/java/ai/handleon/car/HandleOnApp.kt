package ai.handleon.car

import android.app.Application
import android.content.Context

class HandleOnApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** 수동 DI. 이후 Task 들이 저장소·엔진 실행기·서버 클라이언트를 여기에 붙인다. */
class AppGraph(val context: Context)
