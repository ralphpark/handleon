package ai.handleon.car

import android.app.Application
import android.content.Context
import androidx.room.Room
import ai.handleon.car.engine.EngineRunner
import ai.handleon.car.store.Db
import ai.handleon.car.store.TripRepository

class HandleOnApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** 수동 DI. 저장소는 처음 사용할 때 열며 실행기 하나를 공유한다. */
class AppGraph(val context: Context) {
    val db: Db by lazy { Room.databaseBuilder(context.applicationContext, Db::class.java, "handleon.db").build() }
    val tripRepository: TripRepository by lazy { TripRepository(db) }
    val engineRunner: EngineRunner by lazy { EngineRunner(tripRepository) }
}
