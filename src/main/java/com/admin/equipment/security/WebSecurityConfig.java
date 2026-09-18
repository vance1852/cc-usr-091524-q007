package com.admin.equipment.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/** 注册角色门槛拦截器与当前用户参数解析器。 */
@Configuration
public class WebSecurityConfig implements WebMvcConfigurer {

    private final RoleAuthorizationInterceptor roleInterceptor;
    private final CurrentUserArgumentResolver currentUserResolver;

    public WebSecurityConfig(RoleAuthorizationInterceptor roleInterceptor,
                              CurrentUserArgumentResolver currentUserResolver) {
        this.roleInterceptor = roleInterceptor;
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }
}
