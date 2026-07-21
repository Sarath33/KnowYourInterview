// Deprecated: CORS moved to SecurityConfig#corsConfigurationSource() so it's wired
// into Spring Security's filter chain (WebMvcConfigurer#addCorsMappings alone doesn't
// work once Security is in front of MVC — see the comment on that bean for why).
// Safe to delete this file.
package com.knowyourinterview.api.config;
