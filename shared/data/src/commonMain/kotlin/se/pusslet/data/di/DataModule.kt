package se.pusslet.data.di

import org.koin.core.module.Module
import org.koin.dsl.module
import se.pusslet.data.db.PussletDatabase

/** Platform-independent data bindings. The platform module must provide [PussletDatabase] and the HttpClient. */
val dataModule: Module = module {
    single { get<PussletDatabase>().preferenceDao() }
}
