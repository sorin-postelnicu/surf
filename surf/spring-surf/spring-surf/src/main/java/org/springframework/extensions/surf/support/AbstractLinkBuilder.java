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

package org.springframework.extensions.surf.support;

import java.util.Map;

import org.springframework.extensions.config.WebFrameworkConfigElement;
import org.springframework.extensions.surf.LinkBuilder;
import org.springframework.extensions.surf.ModelObjectService;
import org.springframework.extensions.surf.RequestContext;
import org.springframework.extensions.surf.WebFrameworkServiceRegistry;
import org.springframework.extensions.surf.resource.ResourceService;

/**
 * <p>Abstract base class for LinkBuilder implementations.  This
 * is provided as a convenience to developers who wish to build their
 * own custom LinkBuilder variations.
 * </p><p>
 * The Link Builder defines methods that are used generically to
 * construct links to other pages, page types or objects within the
 * system.
 * </p>
 * In general, links are either to specific "known" pages or to
 * page placeholders that must be resolved when the link is clicked.
 * </p><p>
 * Example - a link to a page:
 * </p><ul>
 * <li>String link = builder.page(context, "homepageInstance");</li>
 * </ul>
 * 
 * @author muzquiano
 * @author David Draper
 */
public abstract class AbstractLinkBuilder extends BaseFactoryBean implements LinkBuilder
{
    /**
     * <p>This constructor has been deprecated because it uses the deprecated <code>WebFrameworkServiceRegistry</code>
     * to obtain the actual Spring bean elements needed by the <code>AbstractLinkBuilder</code>.
     * 
     * @param serviceRegistry WebFrameworkServiceRegistry
     * @deprecated
     */
    public AbstractLinkBuilder(WebFrameworkServiceRegistry serviceRegistry)
    {
        this(serviceRegistry.getWebFrameworkConfiguration(), serviceRegistry.getModelObjectService(), serviceRegistry.getResourceService());
    } 
    
    /**
     * <p>This is the preferred constructor to use when instantiating an <code>AbstractLinkBuilder</code>. By supplying the
     * <code>WebFrameworkConfigElement</code>, <code>ModelObjectService</code> and <code>ResourceService</code> arguments
     * directly (rather than obtaining them from the <code>WebFrameworkServiceRegistry</code>) you can have link builders
     * using different services.</p>
     * 
     * @param webFrameworkConfigElement WebFrameworkConfigElement
     * @param modelObjectService ModelObjectService
     * @param resourceService ResourceService
     */
    public AbstractLinkBuilder(WebFrameworkConfigElement webFrameworkConfigElement, 
                               ModelObjectService modelObjectService,
                               ResourceService resourceService)
    {
        super(webFrameworkConfigElement, modelObjectService, resourceService);
    }
    
    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#page(org.springframework.extensions.surf.RequestContext, java.lang.String)
     */
    public String page(RequestContext context, String pageId)
    {
        String formatId = getWebFrameworkConfiguration().getDefaultFormatId();
        return page(context, pageId, formatId);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#page(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String)
     */
    public String page(RequestContext context, String pageId, 
            String formatId)
    {
        return page(context, pageId, formatId, null);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#page(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String, java.lang.String)
     */
    public String page(RequestContext context, String pageId, 
            String formatId, String objectId)
    {
        return page(context, pageId, formatId, objectId, null);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#page(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String, java.lang.String, java.util.Map)
     */
    public abstract String page(RequestContext context, String pageId, 
            String formatId, String objectId, Map<String, String> params);
    
    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#pageType(org.springframework.extensions.surf.RequestContext, java.lang.String)
     */
    public String pageType(RequestContext context, String pageTypeId)
    {
        String formatId = getWebFrameworkConfiguration().getDefaultFormatId();
        return pageType(context, pageTypeId, formatId);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#pageType(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String)
     */
    public String pageType(RequestContext context, String pageTypeId, 
            String formatId)
    {
        return pageType(context, pageTypeId, formatId, null);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#pageType(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String, java.lang.String)
     */
    public String pageType(RequestContext context, String pageTypeId, 
            String formatId, String objectId)
    {
        return pageType(context, pageTypeId, formatId, objectId, null);
    }
    
    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#pageType(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String, java.lang.String, java.util.Map)
     */
    public abstract String pageType(RequestContext context, String pageTypeId, 
            String formatId, String objectId, Map<String, String> params);

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#object(org.springframework.extensions.surf.RequestContext, java.lang.String)
     */
    public String object(RequestContext context, String objectId)
    {
        String formatId = getWebFrameworkConfiguration().getDefaultFormatId();
        return object(context, objectId, formatId);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#object(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String)
     */
    public String object(RequestContext context, String objectId,
            String formatId)
    {
        return object(context, objectId, formatId, null);
    }

    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#object(org.springframework.extensions.surf.RequestContext, java.lang.String, java.lang.String, java.util.Map)
     */
    public abstract String object(RequestContext context, String objectId,
            String formatId, Map<String, String> params);
    
    /* (non-Javadoc)
     * @see org.springframework.extensions.surf.LinkBuilder#resource(org.springframework.extensions.surf.RequestContext, java.lang.String)
     */
    public abstract String resource(RequestContext context, String uri);    
}
