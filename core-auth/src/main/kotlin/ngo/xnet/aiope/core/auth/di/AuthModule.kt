package ngo.xnet.aiope.core.auth.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ngo.xnet.aiope.core.auth.AuthRepository
import ngo.xnet.aiope.core.auth.AuthSettings
import ngo.xnet.aiope.core.auth.BiometricUnlock
import ngo.xnet.aiope.core.auth.KeystoreIdentity
import ngo.xnet.aiope.core.auth.LocalAuthRepository
import ngo.xnet.aiope.core.auth.LocalVerifier
import ngo.xnet.aiope.core.auth.SecurityKeyAuthenticator
import ngo.xnet.aiope.core.auth.TotpEnrollment
import ngo.xnet.aiope.core.auth.Verifier
import ngo.xnet.aiope.core.preferences.Preferences

@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

  @Provides @Singleton
  fun provideKeystoreIdentity(): KeystoreIdentity = KeystoreIdentity()

  @Provides @Singleton
  fun provideAuthSettings(@ApplicationContext context: Context, preferences: Preferences): AuthSettings =
    AuthSettings(context, preferences)

  @Provides @Singleton
  fun provideTotp(@ApplicationContext context: Context): TotpEnrollment = TotpEnrollment(context)

  @Provides @Singleton
  fun provideBiometric(): BiometricUnlock = BiometricUnlock()

  @Provides @Singleton
  fun provideSecurityKey(@ApplicationContext context: Context): SecurityKeyAuthenticator =
    SecurityKeyAuthenticator(context)

  @Provides @Singleton
  fun provideVerifier(@ApplicationContext context: Context, identity: KeystoreIdentity): Verifier =
    LocalVerifier(context, identity)

  @Provides @Singleton
  fun provideAuthRepository(
    @ApplicationContext context: Context,
    settings: AuthSettings,
    identity: KeystoreIdentity,
    biometric: BiometricUnlock,
    totp: TotpEnrollment,
    securityKey: SecurityKeyAuthenticator,
  ): AuthRepository = LocalAuthRepository(context, settings, identity, biometric, totp, securityKey)
}
