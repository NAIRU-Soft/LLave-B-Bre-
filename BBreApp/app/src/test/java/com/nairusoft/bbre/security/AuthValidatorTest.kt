package com.nairusoft.bbre.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthValidatorTest {

    @Test
    fun validateUsername_rejectsEmptyAndWhitespace() {
        assertEquals(AuthValidator.UsernameError.EMPTY, AuthValidator.validateUsername(""))
        assertEquals(
            AuthValidator.UsernameError.CONTAINS_WHITESPACE,
            AuthValidator.validateUsername("juan perez")
        )
        assertNull(AuthValidator.validateUsername("juanperez"))
    }

    @Test
    fun validatePassword_enforcesMinLengthAndNoSpaces() {
        assertEquals(AuthValidator.PasswordError.EMPTY, AuthValidator.validatePassword(""))
        assertEquals(
            AuthValidator.PasswordError.CONTAINS_WHITESPACE,
            AuthValidator.validatePassword("clave 1234")
        )
        assertEquals(AuthValidator.PasswordError.TOO_SHORT, AuthValidator.validatePassword("1234567"))
        assertNull(AuthValidator.validatePassword("12345678"))
        assertNull(AuthValidator.validatePassword("ClaveSegura2026!"))
    }
}
