/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.codehaus.mojo.flatten;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.model.Activation;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileInjector;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.flatten.model.resolution.FlattenModelResolver;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.XmlStreamReader;
import org.codehaus.plexus.util.xml.pull.MXParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParser;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.DefaultHandler2;

/**
 * This MOJO realizes the goal <code>flatten</code> that generates the flattened POM and {@link #isUpdatePomFile()
 * potentially updates the POM file} so that the current {@link MavenProject}'s {@link MavenProject#getFile() file}
 * points to the flattened POM instead of the original <code>pom.xml</code> file.<br/>
 * The flattened POM is a reduced version of the original POM with the focus to contain only the important information
 * for consuming it. Therefore information that is only required for maintenance by developers and to build the project
 * artifact(s) are stripped. <br/>
 * Starting from here we specify how the flattened POM is created from the original POM and its project:<br/>
 * <table border="1">
 * <tr>
 * <th>Element</th>
 * <th>Transformation</th>
 * <th>Note</th>
 * </tr>
 * <tr>
 * <td>{@link Model#getModelVersion() modelVersion}</td>
 * <td>Fixed to "4.0.0"</td>
 * <td>New maven versions will once be able to evolve the model version without incompatibility to older versions if
 * flattened POMs get deployed.</td>
 * </tr>
 * <tr>
 * <td>
 * {@link Model#getGroupId() groupId}<br/>
 * {@link Model#getArtifactId() artifactId}<br/>
 * {@link Model#getVersion() version}<br/>
 * {@link Model#getPackaging() packaging}<br/>
 * </td>
 * <td>resolved</td>
 * <td>copied to the flattened POM but with inheritance from {@link Model#getParent() parent} as well as with all
 * variables and defaults resolved. These elements are technically required for consumption.</td>
 * </tr>
 * <tr>
 * <td>
 * {@link Model#getLicenses() licenses}<br/>
 * </td>
 * <td>resolved</td>
 * <td>copied to the flattened POM but with inheritance from {@link Model#getParent() parent} as well as with all
 * variables and defaults resolved. Unlike the above elements the licenses would not be required in flattened POM.
 * However, they make sense for publication and deployment and are important for consumers of your artifact.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getDependencies() dependencies}</td>
 * <td>resolved specially</td>
 * <td>flattened POM contains the actual dependencies of the project. Test dependencies are removed. Variables and
 * {@link Model#getDependencyManagement() dependencyManagement} is resolved to get fixed dependency attributes
 * (especially {@link Dependency#getVersion() version}). If {@link #embedBuildProfileDependencies} is set to
 * <code>true</code>, then also build-time driven {@link Profile}s will be evaluated and may add {@link Dependency
 * dependencies}. For further details see {@link Profile}s below.</td>
 * </tr>
 * <tr>
 * <td>{@link Model#getProfiles() profiles}</td>
 * <td>resolved specially</td>
 * <td>only the {@link Activation} and the {@link Dependency dependencies} of a {@link Profile} are copied to the
 * flattened POM. If you set the parameter {@link #embedBuildProfileDependencies} to <code>true</code> then only
 * profiles {@link Activation activated} by {@link Activation#getJdk() JDK} or {@link Activation#getOs() OS} will be
 * added to the flattened POM while the other profiles are triggered by the current build setup and if activated their
 * impact on dependencies is embedded into the resulting flattened POM.</td>
 * </tr>
 * <tr>
 * <td>
 * {@link Model#getName() name}<br/>
 * {@link Model#getDescription() description}<br/>
 * {@link Model#getUrl() url}<br/>
 * {@link Model#getInceptionYear() inceptionYear}<br/>
 * {@link Model#getScm() scm}<br/>
 * {@link Model#getParent() parent}<br/>
 * {@link Model#getBuild() build}<br/>
 * {@link Model#getDependencyManagement() dependencyManagement}<br/>
 * {@link Model#getDistributionManagement() distributionManagement}<br/>
 * {@link Model#getCiManagement() ciManagement}<br/>
 * {@link Model#getProperties() properties}<br/>
 * {@link Model#getModules() modules}<br/>
 * {@link Model#getPluginRepositories() pluginRepositories}<br/>
 * {@link Model#getPrerequisites() prerequisites}<br/>
 * {@link Model#getRepositories() repositories}<br/>
 * {@link Model#getIssueManagement() issueManagement}<br/>
 * {@link Model#getReporting() reporting}</td>
 * <td>removed</td>
 * <td>Will be completely stripped and never occur in a flattened POM.</td>
 * </tr>
 * </table>
 * 
 * @author Joerg Hohwiller (hohwille at users.sourceforge.net)
 */
@SuppressWarnings( "deprecation" )
@Mojo( name = "flatten", requiresProject = true, requiresDirectInvocation = false, executionStrategy = "once-per-session", requiresDependencyCollection = ResolutionScope.RUNTIME )
public class FlattenMojo
    extends AbstractMojo
{

    /**
     * The Maven Project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    private MavenProject project;

    /**
     * The flag to indicate if the generated flattened POM shall be set as POM file to the current project. By default
     * this is only done for projects with packaging other than <code>pom</code>. You may want to also do this for
     * <code>pom</code> packages projects by setting this parameter to <code>true</code> or you can use
     * <code>false</code> in order to only generate the flattened POM but never set it as POM file.
     */
    @Parameter( property = "updatePomFile" )
    private Boolean updatePomFile;

    /**
     * The directory where the generated flattened POM file will be written to.
     */
    @Parameter( defaultValue = "${project.build.directory}" )
    private File outputDirectory;

    /**
     * The filename of the generated flattened POM file.
     */
    @Parameter( property = "flattenedPomFilename", defaultValue = "flattened-pom.xml" )
    private String flattenedPomFilename;

    /** The {@link ArtifactRepository} required to resolve POM using {@link #modelBuilder}. */
    @Parameter( defaultValue = "${localRepository}", readonly = true, required = true )
    private ArtifactRepository localRepository;

    /**
     * Profiles activated by OS or JDK are valid ways to have different dependencies per environment. However, profiles
     * activated by property of file are less clear. When setting this parameter to <code>true</code>, the latter
     * dependencies will be written as direct dependencies of the project. <strong>This is not how Maven2 and Maven3
     * handles dependencies</strong>. When keeping this property <code>false</code>, all profiles will stay in the
     * flattened-pom.
     */
    @Parameter( defaultValue = "false" )
    private Boolean embedBuildProfileDependencies;

    /** The ArtifactFactory required to resolve POM using {@link #modelBuilder}. */
    // Neither ArtifactFactory nor DefaultArtifactFactory tells what to use instead
    @Component
    private ArtifactFactory artifactFactory;

    /** The {@link DefaultModelBuilder} used to resolve the POM in order to extract flattened POM data easily. */
    @Component( role = ModelBuilder.class )
    private DefaultModelBuilder modelBuilder;

    /**
     * The constructor.
     */
    public FlattenMojo()
    {

        super();
    }

    /**
     * {@inheritDoc}
     */
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {

        getLog().info( "Generating flattened POM of project " + this.project.getId() + "..." );

        File originalPomFile = this.project.getFile();
        Model flattenedPom = createFlattenedPom( originalPomFile );
        String headerComment = extractHeaderComment( originalPomFile );

        File flattenedPomFile = new File( this.outputDirectory, this.flattenedPomFilename );
        writePom( flattenedPom, flattenedPomFile, headerComment );

        if ( isUpdatePomFile() )
        {
            this.project.setFile( flattenedPomFile );
        }
    }

    /**
     * This method extracts the XML header comment if available.
     * 
     * @param xmlFile is the XML {@link File} to parse.
     * @return the XML comment between the XML header declaration and the root tag or <code>null</code> if NOT
     *         available.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected String extractHeaderComment( File xmlFile )
        throws MojoExecutionException
    {

        try
        {
            SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            SaxHeaderCommentHandler handler = new SaxHeaderCommentHandler();
            parser.setProperty( "http://xml.org/sax/properties/lexical-handler", handler );
            parser.parse( xmlFile, handler );
            return handler.getHeaderComment();
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Failed to parse XML from " + xmlFile, e );
        }
    }

    /**
     * This method extracts the XML header comment if available.
     * 
     * @param xmlFile is the XML {@link File} to parse.
     * @return the XML comment between the XML header declaration and the root tag or <code>null</code> if NOT
     *         available.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected String extractHeaderCommentUsingXppNotWorking( File xmlFile )
        throws MojoExecutionException
    {

        // Actually StAX would be the standard to use. However, this is what comes with maven...
        MXParser xpp = new MXParser();
        XmlStreamReader inStream = null;
        try
        {
            inStream = new XmlStreamReader( xmlFile );
            xpp.setInput( inStream );
            int eventType = -1;
            do
            {
                eventType = xpp.next();
                System.out.println( "XPP Event: " + eventType );
                if ( eventType == XmlPullParser.COMMENT )
                {
                    return xpp.getText();
                }
            }
            while ( eventType != XmlPullParser.START_TAG );
            // no comment before root tag...
            return null;
        }
        catch ( XmlPullParserException e )
        {
            // should never happen as we already parsed the same XML 2 times before...
            throw new MojoExecutionException( "Failed to parse XML of " + xmlFile, e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to read " + xmlFile, e );
        }
        finally
        {
            IOUtil.close( inStream );
        }
    }

    /**
     * Writes the given POM {@link Model} to the given {@link File}.
     * 
     * @param pom the {@link Model} of the POM to write.
     * @param pomFile the {@link File} where to write the given POM will be written to. {@link File#getParentFile()
     *            Parent directories} are {@link File#mkdirs() created} automatically.
     * @param headerComment is the content of a potential XML comment at the top of the XML (after XML declaration and
     *            before root tag). May be <code>null</code> if not present and to be omitted in target POM.
     * @throws MojoExecutionException if the operation failed (e.g. due to an {@link IOException}).
     */
    protected void writePom( Model pom, File pomFile, String headerComment )
        throws MojoExecutionException
    {

        File parentFile = pomFile.getParentFile();
        if ( !parentFile.exists() )
        {
            boolean success = parentFile.mkdirs();
            if ( !success )
            {
                throw new MojoExecutionException( "Failed to create directory " + pomFile.getParent() );
            }
        }
        // MavenXpp3Writer could internally add the comment but does not expose such feature to API!
        // Instead we have to write POM XML to String and do post processing on that :(
        MavenXpp3Writer pomWriter = new MavenXpp3Writer();
        StringWriter stringWriter = new StringWriter( 4096 );
        try
        {
            pomWriter.write( stringWriter, pom );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Internal I/O error!", e );
        }
        StringBuffer buffer = stringWriter.getBuffer();
        if ( !StringUtils.isEmpty( headerComment ) )
        {
            int projectStartIndex = buffer.indexOf( "<project" );
            if ( projectStartIndex >= 0 )
            {
                buffer.insert( projectStartIndex, "<!--" + headerComment + "-->\n" );
            }
            else
            {
                getLog().warn( "POM XML post-processing failed: no project tag found!" );
            }
        }
        writeStringToFile( buffer.toString(), pomFile, pom.getModelEncoding() );
    }

    /**
     * Writes the given <code>data</code> to the given <code>file</code> using the specified <code>encoding</code>.
     * 
     * @param data is the {@link String} to write.
     * @param file is the {@link File} to write to.
     * @param encoding is the encoding to use for writing the file.
     * @throws MojoExecutionException if anything goes wrong.
     */
    protected void writeStringToFile( String data, File file, String encoding )
        throws MojoExecutionException
    {

        OutputStream outStream = null;
        Writer writer = null;
        try
        {
            outStream = new FileOutputStream( file );
            writer = new OutputStreamWriter( outStream, encoding );
            writer.write( data );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to write to " + file, e );
        }
        finally
        {
            // resource-handling not perfectly solved but we do not want to require java 1.7
            // and this is not a server application.
            IOUtil.close( writer );
            IOUtil.close( outStream );
        }
    }

    /**
     * This method creates the flattened POM what is the main task of this plugin.
     * 
     * @param pomFile is the name of the original POM file to read and transform.
     * @return the {@link Model} of the flattened POM.
     * @throws MojoExecutionException if anything goes wrong (e.g. POM can not be processed).
     */
    protected Model createFlattenedPom( File pomFile )
        throws MojoExecutionException
    {

        FlattenModelResolver resolver = new FlattenModelResolver( this.localRepository, this.artifactFactory );
        ModelBuildingRequest buildingRequest =
            new DefaultModelBuildingRequest().setPomFile( pomFile ).setModelResolver( resolver );

        ModelBuildingResult buildingResult;
        try
        {
            ProfileInjector profileInjector = new ProfileInjector()
            {

                public void injectProfile( Model model, Profile profile, ModelBuildingRequest request,
                                           ModelProblemCollector problems )
                {

                    // do nothing
                }
            };
            ProfileSelector profileSelector = new ProfileSelector()
            {

                public List<Profile> getActiveProfiles( Collection<Profile> profiles, ProfileActivationContext context,
                                                        ModelProblemCollector problems )
                {

                    List<Profile> activeProfiles = new ArrayList<Profile>( profiles.size() );

                    for ( Profile profile : profiles )
                    {
                        Activation activation = profile.getActivation();
                        if ( !isEmbedBuildProfileDependencies() || isBuildTimeDriven( activation ) )
                        {
                            activeProfiles.add( profile );
                        }
                    }

                    return activeProfiles;
                }
            };
            this.modelBuilder.setProfileInjector( profileInjector ).setProfileSelector( profileSelector );
            buildingResult = this.modelBuilder.build( buildingRequest );
        }
        catch ( ModelBuildingException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        Model effectiveModel = buildingResult.getEffectiveModel();

        // actually we would need a copy of the 4.0.0 model in a separate package (version_4_0_0 subpackage).
        Model model = new Model();

        // keep original encoding (we could also normalize to UTF-8 here)
        String modelEncoding = effectiveModel.getModelEncoding();
        if ( StringUtils.isEmpty( modelEncoding ) )
        {
            modelEncoding = "UTF-8";
        }
        model.setModelEncoding( modelEncoding );

        // fixed to 4.0.0 forever :)
        model.setModelVersion( "4.0.0" );

        // GAV values have to be fixed without variables, etc.
        model.setGroupId( effectiveModel.getGroupId() );
        model.setArtifactId( effectiveModel.getArtifactId() );
        model.setVersion( effectiveModel.getVersion() );

        // general attributes also need no dynamics/variables
        model.setPackaging( effectiveModel.getPackaging() );

        if ( "maven-plugin".equals( effectiveModel.getPackaging() ) )
        {
            model.setPrerequisites( effectiveModel.getPrerequisites() );
        }

        // copy by reference - if model changes this code has to explicitly create the new elements
        model.setLicenses( effectiveModel.getLicenses() );

        // transform dependencies...
        List<Dependency> dependencies = createFlattenedDependencies( effectiveModel );
        model.setDependencies( dependencies );

        // transform profiles...
        for ( Profile profile : effectiveModel.getProfiles() )
        {
            if ( !isEmbedBuildProfileDependencies() || !isBuildTimeDriven( profile.getActivation() ) )
            {
                Profile strippedProfile = new Profile();
                strippedProfile.setId( profile.getId() );
                strippedProfile.setActivation( profile.getActivation() );
                strippedProfile.setDependencies( profile.getDependencies() );
                // TODO hohwille why do we copy repositories here when we strip them from project?
                strippedProfile.setRepositories( profile.getRepositories() );
                model.addProfile( strippedProfile );
            }
        }
        return model;
    }

    /**
     * @return the value of {@link #embedBuildProfileDependencies}.
     */
    protected boolean isEmbedBuildProfileDependencies()
    {

        return this.embedBuildProfileDependencies.booleanValue();
    }

    /**
     * @param activation is the {@link Activation} of a {@link Profile}.
     * @return <code>true</code> if the given {@link Activation} is build-time driven, <code>false</code> otherwise (if
     *         it is triggered by OS or JDK).
     */
    protected boolean isBuildTimeDriven( Activation activation )
    {

        if ( activation == null )
        {
            return true;
        }
        if ( StringUtils.isEmpty( activation.getJdk() ) && ( activation.getOs() == null ) )
        {
            return true;
        }
        return false;
    }

    /**
     * Creates the {@link List} of {@link Dependency dependencies} for the flattened POM. These are all resolved
     * {@link Dependency dependencies} except for those added from {@link Profile profiles}.
     * 
     * @param effectiveModel is the effective POM {@link Model} to process.
     * @return the {@link List} of {@link Dependency dependencies}.
     */
    protected List<Dependency> createFlattenedDependencies( Model effectiveModel )
    {

        List<Dependency> flattenedDependencies = new ArrayList<Dependency>();
        // resolve all direct and inherited dependencies...
        createFlattenedDependencies( effectiveModel, flattenedDependencies );
        if ( isEmbedBuildProfileDependencies() )
        {
            Model projectModel = this.project.getModel();
            Dependencies modelDependencies = new Dependencies();
            modelDependencies.addAll( projectModel.getDependencies() );
            for ( Profile profile : projectModel.getProfiles() )
            {
                // build-time driven activation (by property or file)?
                if ( isBuildTimeDriven( profile.getActivation() ) )
                {
                    List<Dependency> profileDependencies = profile.getDependencies();
                    for ( Dependency profileDependency : profileDependencies )
                    {
                        if ( modelDependencies.contains( profileDependency ) )
                        {
                            // our assumption here is that the profileDependency has been added to model because of
                            // this build-time driven profile. Therefore we need to add it to the flattened POM.
                            // Non build-time driven profiles will remain in the flattened POM with their dependencies
                            // and
                            // allow dynamic dependencies due to OS or JDK.
                            flattenedDependencies.add( profileDependency );
                        }
                    }
                }
            }
            getLog().debug( "Resolved " + flattenedDependencies.size() + " dependency/-ies for flattened POM." );
        }
        return flattenedDependencies;
    }

    /**
     * Collects the resolved {@link Dependency dependencies} from the given <code>effectiveModel</code>.
     * 
     * @param effectiveModel is the effective POM {@link Model} to process.
     * @param flattenedDependencies is the {@link List} where to add the collected {@link Dependency dependencies}.
     */
    protected void createFlattenedDependencies( Model effectiveModel, List<Dependency> flattenedDependencies )
    {

        getLog().debug( "Resolving dependencies of " + effectiveModel.getId() );
        // this.project.getDependencies() already contains the inherited dependencies but also those from profiles
        // List<Dependency> projectDependencies = currentProject.getOriginalModel().getDependencies();
        List<Dependency> projectDependencies = effectiveModel.getDependencies();
        for ( Dependency projectDependency : projectDependencies )
        {
            Dependency flattenedDependency = createFlattenedDependency( projectDependency );
            if ( flattenedDependency != null )
            {
                flattenedDependencies.add( flattenedDependency );
            }
        }
    }

    /**
     * @param projectDependency is the project {@link Dependency}.
     * @return the flattened {@link Dependency} or <code>null</code> if the given {@link Dependency} is NOT relevant for
     *         flattened POM.
     */
    protected Dependency createFlattenedDependency( Dependency projectDependency )
    {

        return "test".equals( projectDependency.getScope() ) ? null : projectDependency;
    }

    /**
     * @return <code>true</code> if the generated flattened POM shall be {@link MavenProject#setFile(java.io.File) set}
     *         as POM artifact of the {@link MavenProject}, <code>false</code> otherwise.
     */
    protected boolean isUpdatePomFile()
    {

        if ( this.updatePomFile == null )
        {
            return !this.project.getPackaging().equals( "pom" );
        }
        else
        {
            return this.updatePomFile.booleanValue();
        }
    }

    /**
     * This class is a simple SAX handler that extracts the first comment located before the root tag in an XML
     * document.
     */
    private class SaxHeaderCommentHandler
        extends DefaultHandler2
    {

        /** <code>true</code> if root tag has already been visited, <code>false</code> otherwise. */
        private boolean rootTagSeen;

        /** @see #getHeaderComment() */
        private String headerComment;

        /**
         * The constructor.
         */
        public SaxHeaderCommentHandler()
        {

            super();
            this.rootTagSeen = false;
        }

        /**
         * @return the XML comment from the header of the document or <code>null</code> if not present.
         */
        public String getHeaderComment()
        {

            return this.headerComment;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void comment( char[] ch, int start, int length )
            throws SAXException
        {

            if ( !this.rootTagSeen )
            {
                if ( this.headerComment == null )
                {
                    this.headerComment = new String( ch, start, length );
                }
                else
                {
                    getLog().warn( "Ignoring multiple XML header comment!" );
                }
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startElement( String uri, String localName, String qName, Attributes atts )
            throws SAXException
        {

            this.rootTagSeen = true;
        }
    }

}