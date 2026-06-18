package com.bluecopa.temporalservice.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test

class ApiKeyFilterTest {

    private fun invoke(props: SecurityProperties, header: String?): Pair<HttpServletResponse, FilterChain> {
        val req = mock(HttpServletRequest::class.java)
        val resp = mock(HttpServletResponse::class.java)
        val chain = mock(FilterChain::class.java)
        `when`(req.getHeader(props.headerName)).thenReturn(header)
        `when`(resp.writer).thenReturn(PrintWriter(StringWriter()))
        ApiKeyFilter(props, ObjectMapper()).doFilter(req, resp, chain)
        return resp to chain
    }

    @Test
    fun `correct key passes through`() {
        val props = SecurityProperties(apiKey = "secret", headerName = "X-Api-Key", enabled = true)
        val (_, chain) = invoke(props, "secret")
        verify(chain, times(1)).doFilter(any(), any())
    }

    @Test
    fun `wrong key is rejected with 401`() {
        val props = SecurityProperties(apiKey = "secret", headerName = "X-Api-Key", enabled = true)
        val (resp, chain) = invoke(props, "wrong")
        verify(chain, never()).doFilter(any(), any())
        verify(resp).status = 401
    }

    @Test
    fun `missing key is rejected with 401`() {
        val props = SecurityProperties(apiKey = "secret", headerName = "X-Api-Key", enabled = true)
        val (resp, chain) = invoke(props, null)
        verify(chain, never()).doFilter(any(), any())
        verify(resp).status = 401
    }

    @Test
    fun `blank configured key in filter does not permit all`() {
        val props = SecurityProperties(apiKey = "", headerName = "X-Api-Key", enabled = true)
        val (resp, chain) = invoke(props, "anything")
        verify(chain, never()).doFilter(any(), any())
        verify(resp).status = 401
    }
}
