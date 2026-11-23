package ph.toms.conductor.workprofile

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkProfileBindings {
    @Binds
    abstract fun policyAdmin(impl: AndroidPolicyAdmin): PolicyAdmin

    @Binds
    abstract fun workProfileAck(impl: PrefsWorkProfileAck): WorkProfileAck

    companion object {
        @Provides
        @Singleton
        fun workProfileGate(admin: PolicyAdmin): WorkProfileGate = WorkProfileGate(admin)
    }
}
