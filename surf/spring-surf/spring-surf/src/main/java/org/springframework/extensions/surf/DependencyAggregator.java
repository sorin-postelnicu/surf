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
package org.springframework.extensions.surf;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletContext;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.extensions.config.ConfigElement;
import org.springframework.extensions.config.element.GenericConfigElement;
import org.springframework.extensions.surf.support.ThreadLocalRequestContext;
import org.springframework.extensions.surf.util.CacheReport;
import org.springframework.extensions.surf.util.CacheReporter;
import org.springframework.extensions.webscripts.ScriptConfigModel;
import org.springframework.web.context.WebApplicationContext;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import com.googlecode.concurrentlinkedhashmap.EvictionListener;
import com.googlecode.concurrentlinkedhashmap.Weighers;

/**
 * Bean providing aggregating, compression and caching services for groups of Surf file resource dependencies.
 * 
 * @author David Draper
 * @author Kevin Roast
 */
public class DependencyAggregator implements ApplicationContextAware, CacheReporter
{
    private static final Log logger = LogFactory.getLog(DependencyAggregator.class);
    
    private Boolean isDebugMode = null;
    private Boolean isCollationDebugMode = null;
    
    public static final String FLAGS = "flags";
    public static final String CLIENT_DEBUG = "client-debug";
    public static final String CLIENT_COLLATION_DEBUG = "client-collation-debug";
    
    // This marker would be illegal in a path. When detected in a path it indicates that the "path"
    // is in fact inline JavaScript or CSS to insert into the aggregated results..
    public static final String INLINE_AGGREGATION_MARKER = ">>>";
    
    /** Set the size of the file cache for MD5 checksums */
    public int cacheSize = 256;
    public void setCacheSize(int cacheSize)
    {
        this.cacheSize = cacheSize;
    }
    
    /**
     * The {@link CssImageDataHandler} provides the capability to search through CSS resources and
     * convert all URL references to be Base64 encoded data. 
     */
    private CssImageDataHandler cssImageDataHandler;
    public void setCssImageDataHandler(CssImageDataHandler cssImageDataHandler)
    {
        this.cssImageDataHandler = cssImageDataHandler;
    }

    /**
     * The {@link CssThemeHandler} is used to perform token substitution on supplied CSS source
     * files to allow a single source file to be customized by themes.
     */
    private CssThemeHandler cssThemeHandler;
    public void setCssThemeHandler(CssThemeHandler cssThemeHandler)
    {
        this.cssThemeHandler = cssThemeHandler;
    }
    
    /**
     * The {@link DependencyHandler} provides a service for locating dependency resources and
     * generating checksums against their contents. It also provides caching of those resources.
     */
    private DependencyHandler dependencyHandler;
    public void setDependencyHandler(DependencyHandler dependencyHandler)
    {
        this.dependencyHandler = dependencyHandler;
    }
    
    public DependencyHandler getDependencyHandler()
    {
        return dependencyHandler;
    }
    
    /**
     * The {@link JavaScriptCompressionHandler} provides a service that supplies an implementation
     * of a JavaScript compression library.
     */
    private JavaScriptCompressionHandler javaScriptCompressionHandler;
    public void setJavaScriptCompressionHandler(JavaScriptCompressionHandler javaScriptCompressionHandler)
    {
        this.javaScriptCompressionHandler = javaScriptCompressionHandler;
    }
    
    /**
     * The {@link CSSCompressionHandler} provides a service that supplies an implementation
     * of a CSS compression library.
     */
    private CSSCompressionHandler cssCompressionHandler;
    public void setCssCompressionHandler(CSSCompressionHandler cssCompressionHandler)
    {
        this.cssCompressionHandler = cssCompressionHandler;
    }
    
    private List<String> compressionExclusions;
    
    private List<Pattern> compressionExclusionPatterns = new ArrayList<Pattern>();
    
    public void setCompressionExclusions(List<String> compressionExclusions)
    {
        this.compressionExclusions = compressionExclusions;
        
        // Compile the exclusion patterns when provided to save repeating effort later on...
        for (String exlusion: this.compressionExclusions)
        {
            // Convert ? and * wildcards to regex style...
            String regex = exlusion.replace("?", "(.?)").replace("*", "(.*)");
            compressionExclusionPatterns.add(Pattern.compile(regex));
        }
    }

    /**
     * <p>Indicates whether the client should operate in debug mode. This means that all dependency resources
     * should not be compressed or collated.</p>
     * 
     * @return boolean
     */
    public boolean isDebugMode()
    {
        if (this.isDebugMode == null)
        {
            this.isDebugMode = getDebugFlag(CLIENT_DEBUG, Boolean.FALSE);
        }
        return this.isDebugMode;
    }
    
    /**
     * <p>Indicates whether the client should operate in collation debug mode. This means that collated dependency
     * resources should include the names of the files that have been collated.</p>  
     * 
     * @return boolean
     */
    public boolean isCollationDebugMode()
    {
        if (this.isCollationDebugMode == null)
        {
            this.isCollationDebugMode = getDebugFlag(CLIENT_COLLATION_DEBUG, Boolean.FALSE);
        }
        return this.isCollationDebugMode;
    }
    
    private Boolean getDebugFlag(String element, Boolean defaultValue)
    {
        Boolean debugValue = defaultValue;
        Map<String, ConfigElement> global = scriptConfigModel.getGlobal();
        if (global != null)
        {
            Object flags = global.get(FLAGS);
            if (flags instanceof GenericConfigElement)
            {
                ConfigElement clientDebugElement = ((GenericConfigElement) flags).getChild(element);
                if (clientDebugElement != null)
                {
                    debugValue = Boolean.valueOf(clientDebugElement.getValue());
                }
            }
        }
        
        return debugValue;
    }
    
    private ServletContext servletContext = null;
    
    public ServletContext getServletContext()
    {
        return servletContext;
    }

    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException
    {
        if (applicationContext instanceof WebApplicationContext) 
        {
            this.servletContext =  ((WebApplicationContext) applicationContext).getServletContext();
        }
    }
    
    private ScriptConfigModel scriptConfigModel;
    
    public void setScriptConfigModel(ScriptConfigModel scriptConfigModel)
    {
        this.scriptConfigModel = scriptConfigModel;
    } 
    
    /**
     * This should be set to a list of paths that the {@link DependencyAggregator} should not output error
     * messages about not being able to find. The paths can include RegularExpressionsto support wildcard 
     * matching.
     */
    private List<String> missingFileWarningSuppressionList;
    
    /**
     * Gets the paths to suppress missing file warnings for.
     * 
     * @return A list of paths to suppress warnings for.
     */
    public List<String> getMissingFileWarningSuppressionList()
    {
        return missingFileWarningSuppressionList;
    }

    /**
     * Setter provided for Spring to set the missing file warning suppression list from the configuration.
     * 
     * @param missingFileWarningSuppressionList The list of paths to suppress warnings for.
     */
    public void setMissingFileWarningSuppressionList(List<String> missingFileWarningSuppressionList)
    {
        this.missingFileWarningSuppressionList = missingFileWarningSuppressionList;
    }

    public DependencyAggregator()
    {
    }
    
    public enum CompressionType
    {
        JAVASCRIPT("text/javascript", ".js"), CSS("text/css", ".css");
        
        private final String mimetype;
        private final String fileExtension;
        
        private CompressionType(final String mimetype, final String fileExtension)
        {
            this.mimetype = mimetype;
            this.fileExtension = fileExtension;
        }
    }
    
    /**
     * <p>Generates a single compressed JavaScript resource from the supplied list of paths and
     * returns an MD5 checksum value that should be passed to the browser to when requesting
     * the dependencies from the server. The combined compressed source is cached using the MD5
     * checksum as a key.</p>
     *  
     * @param paths A list of paths to compress and combine into a single resource.
     * @return An MD5 checksum that can be used as a key to retrieve the resource from the cache.
     */
    public String generateJavaScriptDependencies(LinkedHashSet<String> paths)
    {
        return generateDependencies(paths, CompressionType.JAVASCRIPT);
    }
    
    /**
     * <p>Generates a single compressed CSS resource from the supplied list of paths and
     * returns an MD5 checksum value that should be passed to the browser to when requesting
     * the dependencies from the server. The combined compressed source is cached using the MD5
     * checksum as a key.</p>
     *  
     * @param paths A list of paths to compress and combine into a single resource.
     * @return An MD5 checksum that can be used as a key to retrieve the resource from the cache.
     */
    public String generateCSSDependencies(LinkedHashSet<String> paths)
    {
        return generateDependencies(paths, CompressionType.CSS);
    }
    
    /**
     * <p>Retrieves, compresses and combines the requested dependencies into a single
     * resource using the supplied compression type and returns an MD5 checksum that can
     * be used to retrieve the resource from the cache.</p>
     * 
     * @param paths A list of the paths to retrieve, compress and combine.
     * @param compressionType CompressionType
     * @return String
     */
    private String generateDependencies(final LinkedHashSet<String> paths, final CompressionType compressionType)
    {
        String checksum = getCachedChecksumForFileSet(paths);
        if (checksum != null)
        {
            // Re-use the checksum previously generated for this file set...
        }
        else
        {
            boolean cacheByFileSet = true;

            // Iterate over the requested paths and aggregate all the content into a single resource (this
            // will be compressed or uncompressed depending upon the debug mode of the application)...
            final StringBuilder aggregatedFileContents = new StringBuilder(10240);
            for (final String path: paths)
            {
                try
                {
                    if (logger.isDebugEnabled())
                        logger.debug("Aggregating resource path: " + (path.indexOf('\n') == -1 ? path : path.substring(0,path.indexOf('\n'))));
                    
                    String fileContents = null;
                    if (path.startsWith(INLINE_AGGREGATION_MARKER))
                    {
                        aggregatedFileContents.append("\n/*Path=Inline insert...*/\n\n");
                        fileContents = path.substring(INLINE_AGGREGATION_MARKER.length());
                        aggregatedFileContents.append(fileContents);
                        aggregatedFileContents.append("\n\n");
                        
                        cacheByFileSet = false; // If there is any inline dynamic JavaScipt then we shouldn't use the file set as a cache key
                    }
                    else if (this.isDebugMode() || compressionType == CompressionType.CSS) // Never compress CSS - it breaks the LESS processing
                    {
                        // If we're running in debug mode then we still want to aggregate the requested files but that we
                        // want to aggregate them in their uncompressed format...
                        InputStream in = this.dependencyHandler.getResourceInputStream(path);
                        if (in != null)
                        {
                            fileContents = this.dependencyHandler.convertResourceToString(in);
                            aggregatedFileContents.append("\n/*Path=");
                            aggregatedFileContents.append(path);
                            aggregatedFileContents.append("*/\n\n");
                            
                            if (compressionType == CompressionType.CSS)
                            {
                                // For CSS files it's important to adjust URLs to ensure that relative paths are processed
                                // for un-imported CSS file URLs
                                StringBuilder sb = new StringBuilder(fileContents);
                                this.cssImageDataHandler.processCssImages(path, sb);
                                fileContents = processCssImports(path, sb.toString(), new HashSet<String>()).toString();
                                sb = new StringBuilder(fileContents);
                                adjustImageURLs(path, sb);
                                fileContents = sb.toString();
                            }
                            
                            aggregatedFileContents.append(fileContents);
                            aggregatedFileContents.append("\n");
                        }
                    }
                    else
                    {
                        // Retrieve and compress the requested JS file...
                        fileContents = getCompressedFile(path, compressionType);
                        if (fileContents == null)
                        {
                            // The file could not be found, generate an error but don't fail the process.
                            // If a file is requested by a browser that does not exist we would not necessarily
                            // expect the page to fail if it does not truly depend upon that file.
                            logger.error("Could not retrieve path:" + path);
                        }
                        else
                        {
                            // Append the compressed file to the current combined resource...
                            aggregatedFileContents.append(fileContents);
                        }
                    }
                    
                }
                catch (IOException e)
                {
                    logger.error("An exception occurred compressing: " + path);
                }
            }
            
            String combinedDependencies;
            if (compressionType == CompressionType.CSS)
            {
                try
                {
                    combinedDependencies = this.cssThemeHandler.processCssThemes("", aggregatedFileContents);
                }
                catch (IOException e)
                {
                    logger.error("Failed to process themes: " + e.getMessage());
                    combinedDependencies = aggregatedFileContents.toString();
                }
            }
            else
            {
                combinedDependencies = aggregatedFileContents.toString();
            }
            
            // Generate a checksum from the combined dependencies and add it to the cache...
            checksum = this.dependencyHandler.generateCheckSum(combinedDependencies) + compressionType.fileExtension;
            if (logger.isDebugEnabled())
                logger.debug("Checksum for aggregated dependencies: " + checksum);
            DependencyResource resource = new DependencyResource(
                    compressionType.mimetype, combinedDependencies, this.dependencyHandler.getCharset());
            cacheDependencyResource(checksum, resource);
            
            if (cacheByFileSet == true && this.isDebugMode() == false)
            {
                cacheChecksumForFileSet(paths, checksum);
            }
        }
        return checksum;
    }
    
    /**
     * <p>This is a map of String Sets to MD5 checksums. It is maintained in memory for the life cycle of the server.
     * It is used to cache requests for paths against MD5 checksums to prevent those checksums being repeatedly 
     * generated. This works on the assumption that file contents cannot be changed while the server is running. 
     * During development and/or migration the server should be shutdown before updating files.</p> 
     */
    private Map<Set<String>, String> fileSetToMD5Map = null;
    private Map<String, String> compressedJSResources = new HashMap<>(1024);
    private Map<String, String> compressedCSSResources = new HashMap<>(32);
    // NOTE: cache eviction from this map is managed by the fileSetToMD5Map implementation EvictionListener - see getFileSetChecksumCache()
    private Map<String, DependencyResource> combinedDependencyMap = new HashMap<>(256);
    
    // Locks for accessing caches...
    private ReentrantReadWriteLock fileSetToMD5MapLock = new ReentrantReadWriteLock();
    private ReentrantReadWriteLock compressedJSResourcesLock = new ReentrantReadWriteLock();
    private ReentrantReadWriteLock compressedCSSResourcesLock = new ReentrantReadWriteLock();
    private ReentrantReadWriteLock combinedDependencyMapLock = new ReentrantReadWriteLock();
    
    /**
     * This checks the cache to see if the requested set of files has previously been used to generate
     * an aggregated resource. The supplied fileset is updated to include the current theme id to ensure
     * that theme specific resources are returned (the theme id is removed after the cache is checked).
     * 
     * @param fileSet Set<String>
     * @return String
     */
    public String getCachedChecksumForFileSet(Set<String> fileSet)
    {
        String themeId = ThreadLocalRequestContext.getRequestContext().getThemeId();
        fileSet.add(themeId);
        String checksum = this.getFileSetChecksumCache().get(fileSet);
        fileSet.remove(themeId);
        return checksum;
    }
    
    /**
     * Caches a generated aggregated resource checksum against the fileset that it was
     * generated against. The current theme id is added to the fileset to ensure that
     * resources are cached per theme.
     * 
     * @param fileSet Set<String>
     * @param checksum String
     */
    protected void cacheChecksumForFileSet(Set<String> fileSet, String checksum)
    {
        String themeId = ThreadLocalRequestContext.getRequestContext().getThemeId();
        fileSet.add(themeId);
        this.getFileSetChecksumCache().put(fileSet, checksum);
    }
    
    /**
     * Construct the File Set MD5 Checksum Cache
     * Currently this cache is based on a ConcurrentLinkedHashMap impl - to maintain a maximum size and insert order
     * allowing old items to be ejected in a deterministic pattern.
     * 
     * @return cache Map
     */
    protected Map<Set<String>, String> getFileSetChecksumCache()
    {
        if (this.fileSetToMD5Map == null)
        {
            this.fileSetToMD5MapLock.writeLock().lock();
            try
            {
                // check again as multiple threads could have been waiting on the write lock
                if (this.fileSetToMD5Map == null)
                {
                    this.fileSetToMD5Map = new ConcurrentLinkedHashMap.Builder<Set<String>, String>()
                             .maximumWeightedCapacity(this.cacheSize)
                             .concurrencyLevel(16)
                             .weigher(Weighers.singleton())
                             .listener(new EvictionListener<Set<String>, String>() {
                                    /**
                                     * Listener called when a key/value pair is evicted from the cache - we use this to ensure the validity
                                     * of the combinedDependencyMap cache in which data must exist when a user attempts to view a page with
                                     * a previously cached value - as it will have been written to the page as a resource checksum link.
                                     */
                                    @Override
                                    public void onEviction(Set<String> key, String value)
                                    {
                                        // the value in this cache is the 'checksum' which is used as the key in the dependency Map cache
                                        combinedDependencyMapLock.writeLock().lock();
                                        try
                                        {
                                            combinedDependencyMap.remove(value);
                                        }
                                        finally
                                        {
                                            combinedDependencyMapLock.writeLock().unlock();
                                        }
                                    }
                                })
                             .build();
                }
            }
            finally
            {
                this.fileSetToMD5MapLock.writeLock().unlock();
            }
        }
        return this.fileSetToMD5Map;
    }
    
    public String getCachedCompressedJSResource(String path)
    {
        String checksum = null;
        this.compressedJSResourcesLock.readLock().lock();
        try
        {
            checksum = this.compressedJSResources.get(path);
        }
        finally
        {
            this.compressedJSResourcesLock.readLock().unlock();
        }
        return checksum;
    }
    
    protected void cacheCompressedJSResource(String path, String content)
    {
        this.compressedJSResourcesLock.writeLock().lock();
        try
        {
            this.compressedJSResources.put(path, content);
        }
        finally
        {
            this.compressedJSResourcesLock.writeLock().unlock();
        }
    }
    
    /**
     * Attempts to retrieve a previously cached CSS resource. Each CSS resource is cached using the
     * current theme ID as a prefix. This is done so that the same CSS resource is not used when
     * switching themes.
     * 
     * @param path String
     * @return String
     */
    public String getCachedCompressedCssResource(String path)
    {
        String content = null;
        String prefix = ThreadLocalRequestContext.getRequestContext().getThemeId();
        this.compressedCSSResourcesLock.readLock().lock();
        try
        {
            content = this.compressedCSSResources.get(prefix + path);
        }
        finally
        {
            this.compressedCSSResourcesLock.readLock().unlock();
        }
        return content;
    }
    
    /**
     * Caches the supplied CSS resource using a combination of the current Theme ID with
     * the CSS source path. 
     * @param path String
     * @param content String
     */
    protected void cacheCompressedCssResource(String path, String content)
    {
        String prefix = ThreadLocalRequestContext.getRequestContext().getThemeId();
        this.compressedCSSResourcesLock.writeLock().lock();
        try
        {
            this.compressedCSSResources.put(prefix + path, content);
        }
        finally
        {
            this.compressedCSSResourcesLock.writeLock().unlock();
        }
    }
    
    /**
     * Attempts to retrieve a previously stored aggregated resource that has been 
     * mapped to a specific checksum. 
     * 
     * @param checksum The checksum to look in the cache for
     * @return The previously cached {@link DependencyResource} or null if not cached. 
     */
    public DependencyResource getCachedDependencyResource(String checksum)
    {
        this.combinedDependencyMapLock.readLock().lock();
        try
        {
            return this.combinedDependencyMap.get(checksum);
        }
        finally
        {
            this.combinedDependencyMapLock.readLock().unlock();
        }
    }
    
    protected void cacheDependencyResource(String checksum, DependencyResource content)
    {
        this.combinedDependencyMapLock.writeLock().lock();
        try
        {
            this.combinedDependencyMap.put(checksum, content);
        }
        finally
        {
            this.combinedDependencyMapLock.writeLock().unlock();
        }
    }
    
    @Override
    public void clearCaches()
    {
        this.fileSetToMD5MapLock.writeLock().lock();
        try
        {
            // clear the reference for this cache will force a rebuild
            this.fileSetToMD5Map = null;
        }
        finally
        {
            this.fileSetToMD5MapLock.writeLock().unlock();
        }
        this.compressedJSResourcesLock.writeLock().lock();
        try
        {
            this.compressedJSResources.clear();
        }
        finally
        {
            this.compressedJSResourcesLock.writeLock().unlock();
        }
        this.compressedCSSResourcesLock.writeLock().lock();
        try
        {
            this.compressedCSSResources.clear();
        }
        finally
        {
            this.compressedCSSResourcesLock.writeLock().unlock();
        }
        this.combinedDependencyMapLock.writeLock().lock();
        try
        {
            this.combinedDependencyMap.clear();
        }
        finally
        {
            this.combinedDependencyMapLock.writeLock().unlock();
        }
    }
    
    @Override
    public List<CacheReport> report()
    {
        List<CacheReport> reports = new ArrayList<>(4);
        
        long size = 0;
        if (this.fileSetToMD5Map != null)
        {
            this.fileSetToMD5MapLock.writeLock().lock();
            try
            {
                for (Set<String> v : this.fileSetToMD5Map.keySet()) // oddly for this cache, the key is the weight
                {
                    for (String p: v) size += p.length()*2;
                    size += 64;
                }
                reports.add(new CacheReport("fileSetToMD5Map", this.fileSetToMD5Map.size(), size));
            }
            finally
            {
                this.fileSetToMD5MapLock.writeLock().unlock();
            }
        }
        
        size = 0;
        this.compressedJSResourcesLock.writeLock().lock();
        try
        {
            for (String v : this.compressedJSResources.values())
            {
                size += v.length()*2 + 64;
            }
            reports.add(new CacheReport("compressedJSResources", this.compressedJSResources.size(), size));
        }
        finally
        {
            this.compressedJSResourcesLock.writeLock().unlock();
        }
        
        size = 0;
        this.compressedCSSResourcesLock.writeLock().lock();
        try
        {
            for (String v : this.compressedCSSResources.values())
            {
                size += v.length()*2;
            }
            reports.add(new CacheReport("compressedCSSResources", this.compressedCSSResources.size(), size));
        }
        finally
        {
            this.compressedCSSResourcesLock.writeLock().unlock();
        }
        
        size = 0;
        this.combinedDependencyMapLock.writeLock().lock();
        try
        {
            for (DependencyResource d : this.combinedDependencyMap.values())
            {
                size += d.getStoredSize();
            }
            reports.add(new CacheReport("combinedDependencyMap", this.combinedDependencyMap.size(), size));
        }
        finally
        {
            this.combinedDependencyMapLock.writeLock().unlock();
        }
        
        return reports;
    }
    
    /**
     * <p>This method is used to ensure all image URL are correct when CSS files are aggregated together. It does this by 
     * converting relative paths to absolute paths to avoid issues where a relative path becomes invalid following aggregation
     * of the CSS file.</p>
     * @param cssPath The path of the CSS file being aggregated.
     * @param cssContents The contents of the CSS file being aggregated.
     * @throws IOException
     */
    public void adjustImageURLs(String cssPath, StringBuilder cssContents) throws IOException
    {
        String pathPrefix = "";
        int lastForwardSlash = cssPath.lastIndexOf(CssImageDataHandler.FORWARD_SLASH);
        if (lastForwardSlash != -1)
        {
            pathPrefix = cssPath.substring(0, lastForwardSlash);
        }
        else
        {
            // No action required.
        }
        int index = cssContents.indexOf(CssImageDataHandler.URL_OPEN_TARGET_PATTERN);
        while (index != -1)
        {
            int matchingClose = cssContents.indexOf(CssImageDataHandler.URL_CLOSE_TARGET_PATTERN, index + CssImageDataHandler.URL_OPEN_TARGET_PATTERN.length());
            if (matchingClose == -1)
            {
                // This would be a CSS error!
                return;
            }
            else
            {
                // Get the image source and trim any white space...
                String imageSrc = cssContents.substring(index + CssImageDataHandler.URL_OPEN_TARGET_PATTERN.length(), matchingClose).trim();
                
                // Remove opening and closing quotes...
                if (imageSrc.startsWith(CssImageDataHandler.DOUBLE_QUOTES) || imageSrc.startsWith(CssImageDataHandler.SINGLE_QUOTE))
                {
                    imageSrc = imageSrc.substring(1);
                }
                if (imageSrc.endsWith(CssImageDataHandler.DOUBLE_QUOTES) || imageSrc.endsWith(CssImageDataHandler.SINGLE_QUOTE))
                {
                    imageSrc = imageSrc.substring(0, imageSrc.length() -1);
                }
                
                if (imageSrc.startsWith(CssImageDataHandler.DATA_IMAGE_PREFIX) ||
                    imageSrc.toLowerCase().startsWith("http://") || 
                    imageSrc.startsWith(CssImageDataHandler.FORWARD_SLASH))
                {
                    // If the image is data encoded then we just need to move to the index along to the end of the pattern...
                    index = cssContents.indexOf(CssImageDataHandler.URL_OPEN_TARGET_PATTERN, matchingClose);
                }
                else
                {
                    if (imageSrc.startsWith(CssImageDataHandler.FULL_STOP) && !imageSrc.startsWith(CssImageDataHandler.DOUBLE_FULL_STOP))
                    {
                        // The image  source starts with either a single full stop (to indicate relativity to the CSS file) and NOT a double
                        // full stop (to indicate the parent folder of the CSS file) so we need to append this value to the CSS path...
                        imageSrc = imageSrc.substring(1);
                        imageSrc = pathPrefix + imageSrc;
                    }
                    else if (!imageSrc.startsWith(CssImageDataHandler.DOUBLE_FULL_STOP))
                    {
                        // The image source doesn't start with a single full stop, a forward slash or a double full stop so it is assumed
                        // relative to the CSS file location...
                        imageSrc = pathPrefix + CssImageDataHandler.FORWARD_SLASH + imageSrc;
                    }
                    else
                    {
                        String tmp = pathPrefix;
                        while (imageSrc.startsWith(CssImageDataHandler.DOUBLE_FULL_STOP))
                        {
                            imageSrc = imageSrc.substring(3);
                            int lastSlashIndex = tmp.lastIndexOf(CssImageDataHandler.FORWARD_SLASH);
                            if (lastSlashIndex != -1)
                            {
                                tmp = tmp.substring(0, lastSlashIndex);
                            }
                            else
                            {
                                tmp = "";
                            }
                        }
                        
                        if (!tmp.endsWith(CssImageDataHandler.FORWARD_SLASH) && !imageSrc.startsWith(CssImageDataHandler.FORWARD_SLASH))
                        {
                            imageSrc = tmp + CssImageDataHandler.FORWARD_SLASH + imageSrc;
                        }
                        else
                        {
                            imageSrc = tmp + imageSrc;
                        }
                    }
                    
                    
                    if (imageSrc.startsWith(CssImageDataHandler.FORWARD_SLASH))
                    {
                        imageSrc = imageSrc.substring(1);
                    }
                    // Make the path absolute...
                    String prefix = getServletContext().getContextPath() + this.dependencyHandler.getResourceControllerMapping() + CssImageDataHandler.FORWARD_SLASH;
                    imageSrc = prefix + imageSrc;
                    
                    int offset = index + CssImageDataHandler.URL_OPEN_TARGET_PATTERN.length();
                    cssContents.delete(offset, matchingClose);               // Delete the original URL
                    offset = cssImageDataHandler.insert(cssContents, offset, imageSrc); // Add new URL...
                    index = cssContents.indexOf(CssImageDataHandler.URL_OPEN_TARGET_PATTERN, offset);
                }
            }
        }
    }
    
    public static char SINGLE_QUOTE = new Character('\'');
    public static char DOUBLE_QUOTE = new Character('\"');
    
//    public static final Pattern p = Pattern.compile("(@import[\\s\\t]*url[\\s\\t]*\\((.*?)\\)[\\s\\t]*;)");
    public static final Pattern p = Pattern.compile("(@import[\\s\\t]*url[\\s\\t]*\\((.*?)\\))");
    
    /**
     * When aggregating CSS files together its important to process any import statements that are included. The rules of CSS imports
     * are that they must occur before anything else in the CSS file (even comments!) so we need to make sure that all imports are
     * expanded to become the imported file contents
     * 
     * TODO: It would be worth looking into caching processed CSS files.
     *
     * @param cssPath String
     * @param fileContents String
     * @param processedPaths Set<String>
     * @return StringBuffer
     */
    public StringBuffer processCssImports(String cssPath, String fileContents, Set<String> processedPaths)
    {
        // This will hold the updated contents...
        StringBuffer s = new StringBuffer(1024);
        
        // This pattern matches all the CSS imports...
        if (fileContents != null)
        {
            Matcher m = p.matcher(fileContents);
            while (m.find())
            {
                if (m.group(2) != null)
                {
                    StringBuilder path = new StringBuilder(m.group(2).trim());
                    if (path.charAt(0) == SINGLE_QUOTE || path.charAt(0) == DOUBLE_QUOTE)
                    {
                        path.deleteCharAt(0);
                    }
                    char lastChar = path.charAt(path.length()-1);
                    if (lastChar == SINGLE_QUOTE || lastChar == DOUBLE_QUOTE)
                    {
                        path.deleteCharAt(path.length()-1);
                    }
                    
                    String importContents = null;
                    try
                    {
                        // Process the CSS import - CSS is no longer compressed but it is LESS compiled etc.
                        String importPath = this.dependencyHandler.getRelativePath(cssPath, path.toString());
                        importContents = this.getCompressedFile(importPath, CompressionType.CSS);
                        importContents = processCssImport(importContents, cssPath, importPath, processedPaths);
                    }
                    catch (IOException e)
                    {
                        // If there's an exception then don't worry - we just won't replace the contents...
                    }
                    
                    if (importContents != null)
                    {
                        m.appendReplacement(s, importContents);
                    }
                }
                
            }
            
            // Append the remainder of the file.
            m.appendTail(s);
        }
        
        return s;
    }
    
    /**
     * <p>Processes a single CSS import request. This method ensures that infinite loops do not occur. The functionality
     * has been abstracted to a separate method so that it can be called when either in debug or production modes.</p>
     * 
     * @param importContents The current contents of the CSS file being processed.
     * @param cssPath The current path of the CSS file being processed.
     * @param importPath The path requested to be imported within the current CSS file being processed.
     * @param processedPaths A {@link Set} of the paths already processed.
     * @return The processed CSS contents
     * @throws IOException
     */
    protected String processCssImport(String importContents, String cssPath, String importPath, Set<String> processedPaths) throws IOException
    {
        StringBuilder s1 = new StringBuilder();
        
        // Every CSS file could import others - it is therefore important that we recursively process them all...
        if (importPath.equals(cssPath) || processedPaths.contains(importPath))
        {
            // The path to be processed is either the current path or has already been processed - either way this would be
            // the start of an infinite loop and needs to be avoided so don't recurse!!
            s1.append(importContents);
        }
        else
        {
            // Recursively process the requested CSS import...
            processedPaths.add(importPath);
            s1.append(processCssImports(importPath, importContents, processedPaths));
        }
        adjustImageURLs(importPath, s1);
        importContents = s1.toString();
        return importContents;
    }
    
    
    /**
     * Returns the compressed version of the file at the given path using the supplied compression type.
     * 
     * @param path String
     * @param type CompressionType
     * @return String
     * @throws IOException
     */
    String getCompressedFile(String path, CompressionType type) throws IOException
    {
        if (logger.isDebugEnabled())
            logger.debug("Compressing " + path + " as " + type);
        String compressedFile = null;
        if (type == CompressionType.JAVASCRIPT)
        {
            compressedFile = getCachedCompressedJSResource(path);
        }
        else if (type == CompressionType.CSS)
        {
            compressedFile = getCachedCompressedCssResource(path);
        }
        if (compressedFile == null || isDebugMode() == true)
        {
            // Check the compression exclusions to ensure that we really want to compress the file...
            // NOTE: we cannot test for  "isDebugMode() == true"  here as DojoDependencyHandler with fail to correctly regex out
            //       the dojo dependences from files containing comments and line breaks etc.
            if (excludeFileFromCompression(path))
            {
                InputStream in = this.dependencyHandler.getResourceInputStream(path);
                if (in != null)
                {
                    compressedFile = this.dependencyHandler.convertResourceToString(in);
                    if (type == CompressionType.JAVASCRIPT)
                    {
                        cacheCompressedJSResource(path, compressedFile);
                    }
                    else if (type == CompressionType.CSS)
                    {
                        cacheCompressedCssResource(path, compressedFile);
                    }
                }
            }
            else
            {
                // The file hasn't previously been compressed and isn't excluded from compression, let's compress it now...
                InputStream in = this.dependencyHandler.getResourceInputStream(path);
                if (in == null)
                {
                    boolean outputError = true;
                    for (String pathToSuppress: this.missingFileWarningSuppressionList)
                    {
                        if (path.matches(pathToSuppress))
                        {
                            outputError = false;
                            break;
                        }
                    }
                    
                    // We couldn't find the resource - generate an error...
                    if (outputError && logger.isErrorEnabled())
                    {
                        logger.error("Could not find compressed file: " + path);
                    }
                }
                else 
                {
                    try
                    {
                        // Compress the file based on the requested compression type...
                        if (type == CompressionType.JAVASCRIPT)
                        {
                            Reader reader = new InputStreamReader(in, "UTF-8");
                            compressedFile = compressJavaScript(reader);
                            cacheCompressedJSResource(path, compressedFile);
                        }
                        else if (type == CompressionType.CSS)
                        {
                            compressedFile = compressCSSFile(in);
                            cacheCompressedCssResource(path, compressedFile);
                        }
                    }
                    catch (IOException e)
                    {
                        // An exception occurred compressing the file. 
                        if (logger.isWarnEnabled())
                            logger.warn("The file: \"" + path + "\" could not be compressed due to the following error: ", e);
                        
                        // Generate a String of the uncompressed file...
                        compressedFile = IOUtils.toString(in, "UTF-8");
                        if (type == CompressionType.JAVASCRIPT)
                        {
                            cacheCompressedJSResource(path, compressedFile);
                        }
                        else if (type == CompressionType.CSS)
                        {
                            cacheCompressedCssResource(path, compressedFile);
                        }
                    }
                }
            }
        }
        
        return compressedFile;
    }

    /**
     * <p>Compresses the JavaScript file provided by the supplied {@link InputStream} using the {@link JavaScriptCompressionHandler}.</p>
     * 
     * @param reader Reader
     * @return A String representation of the compressed JavaScript file
     * @throws IOException
     */
    public String compressJavaScript(Reader reader) throws IOException
    {
        String compressedFile = null;
        StringWriter out = new StringWriter();

        // This form of debug mode is debugging how JS/CSS files are concatenated together. 
        if (isCollationDebugMode())
        {
           try
           {
               char[] buffer = new char[1024];
               try 
               {
                  int n;
                  while ((n = reader.read(buffer)) != -1) 
                  {
                      out.write(buffer, 0, n);
                  }
               }
               finally 
               {
                  reader.close();
               }
           }
           catch (Exception e)
           {
               logger.error("Compression error: ", e);
           }
        } 
        else
        {
            this.javaScriptCompressionHandler.compress(reader, out);
        }
        compressedFile = out.toString();
        return compressedFile;
    }
    
    /**
     * <p>Compresses the CSS file provided by the supplied {@link InputStream} using the {@link CSSCompressionHandler}.</p>
     * 
     * @param in InputStream
     * @return A String representation of the compressed CSS file
     * @throws IOException
     */
    public String compressCSSFile(InputStream in) throws IOException
    {
        Reader reader = new InputStreamReader(in, "UTF-8");
        StringWriter out = new StringWriter();
        this.cssCompressionHandler.compress(reader, out);
        String compressedFile = out.toString();
        return compressedFile;
    }
    
    /**
     * Checks to see whether or not the path meets any of the compression exclusion criteria. This
     * can be used to prevent attempts to re-compress already compressed files or prevent attempts
     * to compress files that are known to fail under compression.  
     * 
     * @param path The path to check against the filters
     * @return <code>true</code> if the path should be excluded and <code>false</code> otherwise.
     */
    public boolean excludeFileFromCompression(String path)
    {
        boolean exclude = false;
        
        for (Pattern p: this.compressionExclusionPatterns)
        {
            Matcher m = p.matcher(path);
            if (m.matches())
            {
                exclude = true;
                break;
            }
        }
        
        return exclude;
    }
}
