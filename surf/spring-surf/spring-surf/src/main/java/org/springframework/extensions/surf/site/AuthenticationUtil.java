/*
 * Copyright (C) 2005-2015 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.springframework.extensions.surf.site;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.extensions.surf.UserFactory;
import org.springframework.extensions.surf.support.AbstractUserFactory;
import org.springframework.extensions.surf.util.URLEncoder;

/**
 * @author muzquiano
 * @author kevinr
 */
public class AuthenticationUtil
{
    /** cookie names */
    private static final String COOKIE_ALFLOGIN = "alfLogin";
    private static final String COOKIE_ALFUSER = "alfUsername3";
    private static final int TIMEOUT = 60*60*24*7;
    
    private static final String MT_GUEST_PREFIX = AbstractUserFactory.USER_GUEST + "@"; // eg. for MT Share
    
    
    public static void logout(HttpServletRequest request, HttpServletResponse response)
    {
        // invalidate the web session - will remove all session bound objects
        // such as connector sessions, theme settings etc.
        request.getSession().invalidate();
        
        // remove cookie
        if (response != null)
        {
            Cookie userCookie = new Cookie(COOKIE_ALFUSER, "");
            userCookie.setPath(request.getContextPath());
            userCookie.setMaxAge(0);
            response.addCookie(userCookie);
        }
    }
    
    public static void login(HttpServletRequest request, String userId)
    {
        login(request, null, userId, true);
    }
    
    public static void login(HttpServletRequest request, HttpServletResponse response, String userId)
    {
        login(request, response, userId, true);
    }
    
    public static void login(HttpServletRequest request, HttpServletResponse response, String userId, boolean logout)
    {
        login(request, response, userId, logout, true);
    }
    
    public static void login(HttpServletRequest request, HttpServletResponse response, String userId, boolean logout, boolean setLoginCookies)
    {
        if (logout)
        {
            // check whether there is already a user logged in
            String currentUserId = (String) request.getSession().getAttribute(UserFactory.SESSION_ATTRIBUTE_KEY_USER_ID);
            if (currentUserId != null)
            {
                // log out the current user
                logout(request, response);
            }
        }
        
        // place user id onto the session
        request.getSession().setAttribute(UserFactory.SESSION_ATTRIBUTE_KEY_USER_ID, userId);
        
        // set login and last username cookies
        if (response != null && setLoginCookies)
        {
            long timeInSeconds = System.currentTimeMillis() / 1000L;
            Cookie loginCookie = new Cookie(COOKIE_ALFLOGIN, Long.toString(timeInSeconds));
            loginCookie.setPath(request.getContextPath());
            loginCookie.setMaxAge(TIMEOUT);
            response.addCookie(loginCookie);
            
            if (isGuest(userId) == false)
            {
                Cookie userCookie;
                userCookie = new Cookie(COOKIE_ALFUSER, URLEncoder.encode(userId));
                userCookie.setPath(request.getContextPath());
                userCookie.setMaxAge(TIMEOUT);
                response.addCookie(userCookie);
            }
        }
    }
    
    public static void clearUserContext(HttpServletRequest request)
    {
        request.getSession().removeAttribute(UserFactory.SESSION_ATTRIBUTE_KEY_USER_ID);
        request.getSession().removeAttribute(UserFactory.SESSION_ATTRIBUTE_KEY_USER_OBJECT);
    }
    
    public static boolean isAuthenticated(HttpServletRequest request)
    {
        // get user id from the session
        String userId = (String)request.getSession().getAttribute(UserFactory.SESSION_ATTRIBUTE_KEY_USER_ID);
        
        // return whether is non-null and not 'guest'
        return (userId != null && !isGuest(userId));
    }
    
    public static boolean isGuest(String userId)
    {
        // return whether 'guest' (or 'guest@tenant')
        return (userId != null && (UserFactory.USER_GUEST.equals(userId) || userId.startsWith(MT_GUEST_PREFIX)));
    }
    
    public static boolean isExternalAuthentication(HttpServletRequest request)
    {
        return (request.getSession().getAttribute(UserFactory.SESSION_ATTRIBUTE_EXTERNAL_AUTH) != null);
    }
    
    public static String getUserId(HttpServletRequest request)
    {
        return (String)request.getSession().getAttribute(UserFactory.SESSION_ATTRIBUTE_KEY_USER_ID);
    }
    
    /**
     * Helper to return cookie that saves the last login time for the current user.
     * 
     * @param request HttpServletRequest
     * 
     * @return Cookie if found or null if not present
     */
    public static Cookie getLastLoginCookie(HttpServletRequest request)
    {
        return getCookie(request, COOKIE_ALFLOGIN);
    }

    /**
     * Helper to return cookie that saves the last login time for the current user.
     * 
     * @param request HttpServletRequest
     * 
     * @return Cookie if found or null if not present
     */
    public static Cookie getUsernameCookie(HttpServletRequest request)
    {
        return getCookie(request, COOKIE_ALFUSER);
    }
    
    private static Cookie getCookie(HttpServletRequest request, String name)
    {
        Cookie cookie = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
        {
            for (int i=0; i<cookies.length; i++)
            {
                if (name.equals(cookies[i].getName()))
                {
                    // found cookie
                    cookie = cookies[i];
                    break;
                }
            }
        }
        return cookie;
    }
}