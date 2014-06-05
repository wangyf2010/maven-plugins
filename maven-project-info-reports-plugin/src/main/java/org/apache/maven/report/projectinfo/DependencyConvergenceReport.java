package org.apache.maven.report.projectinfo;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.resolver.ArtifactCollector;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.doxia.sink.Sink;
import org.apache.maven.doxia.sink.SinkEventAttributeSet;
import org.apache.maven.doxia.sink.SinkEventAttributes;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.report.projectinfo.dependencies.DependencyVersionMap;
import org.apache.maven.reporting.MavenReportException;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilder;
import org.apache.maven.shared.dependency.tree.DependencyTreeBuilderException;
import org.codehaus.plexus.util.StringUtils;

/**
 * Generates the Dependency Convergence report for reactor builds.
 * 
 * @author <a href="mailto:joakim@erdfelt.com">Joakim Erdfelt</a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton </a>
 * @author <a href="mailto:wangyf2010@gmail.com">Simon Wang </a>
 * @version $Id: DependencyConvergenceReport.java 1367255 2012-07-30 20:01:21Z hboutemy $
 * @since 2.0
 */
@Mojo( name = "dependency-convergence", aggregator = true )
public class DependencyConvergenceReport
    extends AbstractProjectInfoReport
{
    private static final int PERCENTAGE = 100;

    // ----------------------------------------------------------------------
    // Mojo parameters
    // ----------------------------------------------------------------------

    /**
     * The projects in the current build. The effective-POM for each of these projects will written.
     */
    @Parameter( property = "reactorProjects", required = true, readonly = true )
    private List<MavenProject> reactorProjects;

    /**
     * Dependency tree builder, will use it to build dependency tree.
     */
    @Component
    DependencyTreeBuilder dependencyTreeBuilder;

    /**
     * Use it to build dependency(artifact) tree
     */
    @Component
    ArtifactFactory factory;

    /**
     * Use it to get artifact metadata source for dependency tree building.
     */
    @Component
    ArtifactMetadataSource metadataSource;

    /**
     * Artifact collector - takes a set of original artifacts and resolves all of the best versions to use along with
     * their metadata.
     */
    @Component
    ArtifactCollector collector;

    ArtifactFilter filter = null;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /** {@inheritDoc} */
    public String getOutputName()
    {
        return "dependency-convergence";
    }

    @Override
    protected String getI18Nsection()
    {
        return "dependency-convergence";
    }

    @Override
    public boolean canGenerateReport()
    {
        // should generate the convergency report, even its single maven project.
        return reactorProjects.size() >= 1;
    }

    // ----------------------------------------------------------------------
    // Protected methods
    // ----------------------------------------------------------------------

    @Override
    protected void executeReport( Locale locale )
        throws MavenReportException
    {
        Sink sink = getSink();

        sink.head();
        sink.title();

        if ( isReactorBuild() )
        {
            sink.text( getI18nString( locale, "reactor.title" ) );
        }
        else
        {
            sink.text( getI18nString( locale, "title" ) );
        }

        sink.title_();
        sink.head_();

        sink.body();

        sink.section1();

        sink.sectionTitle1();

        if ( isReactorBuild() )
        {
            sink.text( getI18nString( locale, "reactor.title" ) );
        }
        else
        {
            sink.text( getI18nString( locale, "title" ) );
        }

        sink.sectionTitle1_();

        Map<String, List<ReverseDependencyLink>> dependencyMap = getDependencyMap();

        DependencyAnalyzeResult dependencyResult = analyze( dependencyMap );

        // legend
        generateLegend( locale, sink );

        sink.lineBreak();

        // stats
        generateStats( locale, sink, dependencyResult );

        sink.section1_();

        // convergence
        generateConvergence( locale, sink, dependencyResult );

        sink.body_();
        sink.flush();
        sink.close();
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Analyze dependency map to get conflicting dependencies & snapshot dependencies.
     * 
     * @param dependencyMap
     * @return DependencyAnalyzeResult
     */
    private DependencyAnalyzeResult analyze( Map<String, List<ReverseDependencyLink>> dependencyMap )
    {
        DependencyAnalyzeResult dependencyResult = new DependencyAnalyzeResult();

        dependencyResult.setAll( dependencyMap );

        Map<String, List<ReverseDependencyLink>> conflictingDependencyMap = getConflictingDependencies( dependencyMap );
        dependencyResult.setConflicting( conflictingDependencyMap );

        List<ReverseDependencyLink> snapshots = getSnapshotDependencies( dependencyMap );
        dependencyResult.setSnapshots( snapshots );

        return dependencyResult;
    }

    /**
     * Get snapshots dependencies from all dependency map.
     * 
     * @param dependencyMap
     * @return snapshots dependencies
     */
    private List<ReverseDependencyLink> getSnapshotDependencies( Map<String, List<ReverseDependencyLink>> dependencyMap )
    {
        List<ReverseDependencyLink> snapshots = new ArrayList<ReverseDependencyLink>();
        for ( Map.Entry<String, List<ReverseDependencyLink>> entry : dependencyMap.entrySet() )
        {
            List<ReverseDependencyLink> depList = entry.getValue();
            Map<String, List<ReverseDependencyLink>> artifactMap = getSortedUniqueArtifactMap( depList );
            for ( Map.Entry<String, List<ReverseDependencyLink>> artEntry : artifactMap.entrySet() )
            {
                String version = artEntry.getKey();
                boolean isReactorProject = false;

                Iterator<ReverseDependencyLink> iterator = artEntry.getValue().iterator();
                // It if enough to check just the first dependency here, because
                // the dependency is the same in all the RDLs in the List. It's the
                // reactorProjects that are different.
                ReverseDependencyLink rdl = null;
                if ( iterator.hasNext() )
                {
                    rdl = iterator.next();
                    if ( isReactorProject( rdl.getDependency() ) )
                    {
                        isReactorProject = true;
                    }
                }

                if ( version.endsWith( "-SNAPSHOT" ) && !isReactorProject && rdl != null )
                {
                    snapshots.add( rdl );
                }
            }
        }

        return snapshots;
    }

    /**
     * Get conflicting dependencies from all dependency map.
     * 
     * @param dependencyMap
     * @return conflicting dependencies
     */
    private Map<String, List<ReverseDependencyLink>> getConflictingDependencies( Map<String, List<ReverseDependencyLink>> dependencyMap )
    {
        Map<String, List<ReverseDependencyLink>> newMap = new HashMap<String, List<ReverseDependencyLink>>();
        for ( Map.Entry<String, List<ReverseDependencyLink>> entry : dependencyMap.entrySet() )
        {
            if ( entry.getValue().size() <= 1 )
            {
                continue;
            }

            newMap.put( entry.getKey(), entry.getValue() );
        }

        return newMap;
    }

    /**
     * Generate the convergence table for all dependencies
     * 
     * @param locale
     * @param sink
     * @param conflictingDependencyMap
     */
    private void generateConvergence( Locale locale, Sink sink, DependencyAnalyzeResult result )
    {
        sink.section2();

        sink.sectionTitle2();

        if (isReactorBuild())
        {
            sink.text( getI18nString( locale, "convergence.caption" ) );
        }
        else
        {
            sink.text( getI18nString( locale, "convergence.single.caption" ) );
        }
        
        sink.sectionTitle2_();

        // print conflicting dependencies
        for ( Map.Entry<String, List<ReverseDependencyLink>> entry : result.getConflicting().entrySet() )
        {
            String key = entry.getKey();
            List<ReverseDependencyLink> depList = entry.getValue();

            sink.section3();
            sink.sectionTitle3();
            sink.text( key );
            sink.sectionTitle3_();

            generateDependencyDetails( sink, depList );

            sink.section3_();
        }

        // print out snapshots jars
        for ( ReverseDependencyLink dependencyLink : result.getSnapshots() )
        {
            sink.section3();
            sink.sectionTitle3();

            Dependency dep = dependencyLink.getDependency();

            sink.text( dep.getGroupId() + ":" + dep.getArtifactId() );
            sink.sectionTitle3_();

            List<ReverseDependencyLink> depList = new ArrayList<ReverseDependencyLink>();
            depList.add( dependencyLink );
            generateDependencyDetails( sink, depList );

            sink.section3_();
        }

        sink.section2_();
    }

    /**
     * Generate the detail table for a given dependency
     * 
     * @param sink
     * @param depList
     */
    private void generateDependencyDetails( Sink sink, List<ReverseDependencyLink> depList )
    {
        sink.table();

        Map<String, List<ReverseDependencyLink>> artifactMap = getSortedUniqueArtifactMap( depList );

        sink.tableRow();

        sink.tableCell();

        iconError( sink );

        sink.tableCell_();

        sink.tableCell();

        sink.table();

        for ( String version : artifactMap.keySet() )
        {
            sink.tableRow();
            sink.tableCell( new SinkEventAttributeSet( new String[] { SinkEventAttributes.WIDTH, "25%" } ) );
            sink.text( version );
            sink.tableCell_();

            sink.tableCell();
            generateVersionDetails( sink, artifactMap, version );
            sink.tableCell_();

            sink.tableRow_();
        }
        sink.table_();
        sink.tableCell_();

        sink.tableRow_();

        sink.table_();
    }

    /**
     * Generate version details for a given dependency
     * 
     * @param sink
     * @param artifactMap
     * @param version
     */
    private void generateVersionDetails( Sink sink, Map<String, List<ReverseDependencyLink>> artifactMap, String version )
    {
        sink.numberedList( 0 ); // Use lower alpha numbering
        List<ReverseDependencyLink> depList = artifactMap.get( version );
        Collections.sort( depList, new ReverseDependencyLinkComparator() );

        for ( ReverseDependencyLink rdl : depList )
        {
            if ( rdl.getDependencyNode() == null && depList.size() > 1 )
            {
                continue;
            }

            sink.numberedListItem();

            if ( isReactorBuild() )
            {
                MavenProject project = rdl.getProject();
                link( sink, rdl.project.getUrl() );
                sink.text( project.getGroupId() + ":" + project.getArtifactId() + ":" + project.getVersion() );
                link_( sink, rdl.project.getUrl() );
                sink.lineBreak();
            }

            if ( rdl.getDependencyNode() == null )
            {
                Dependency dep = rdl.getDependency();
                sink.text( dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion() );
            }
            else
            {
                buildTreeSink( rdl, sink, isReactorBuild() );
            }

            sink.numberedListItem_();
            if ( isReactorBuild() )
            {
                sink.lineBreak();
            }
        }
        sink.numberedList_();
    }

    /**
     * Produce a Map of relationships between dependencies (its version) and reactor projects. This is the structure of
     * the Map:
     * 
     * <pre>
     * +--------------------+----------------------------------+
     * | key                | value                            |
     * +--------------------+----------------------------------+
     * | version of a       | A List of ReverseDependencyLinks |
     * | dependency         | which each look like this:       |
     * |                    | +------------+-----------------+ |
     * |                    | | dependency | reactor project | |
     * |                    | +------------+-----------------+ |
     * +--------------------+----------------------------------+
     * </pre>
     * 
     * @return A Map of sorted unique artifacts
     */
    private Map<String, List<ReverseDependencyLink>> getSortedUniqueArtifactMap( List<ReverseDependencyLink> depList )
    {
        Map<String, List<ReverseDependencyLink>> uniqueArtifactMap = new TreeMap<String, List<ReverseDependencyLink>>();

        for ( ReverseDependencyLink rdl : depList )
        {
            String key = rdl.getDependency().getVersion();
            List<ReverseDependencyLink> projectList = uniqueArtifactMap.get( key );
            if ( projectList == null )
            {
                projectList = new ArrayList<ReverseDependencyLink>();
            }
            projectList.add( rdl );
            uniqueArtifactMap.put( key, projectList );
        }

        return uniqueArtifactMap;
    }

    /**
     * Generate the legend table
     * 
     * @param locale
     * @param sink
     */
    private void generateLegend( Locale locale, Sink sink )
    {
        sink.table();
        sink.tableCaption();
        sink.bold();
        sink.text( getI18nString( locale, "legend" ) );
        sink.bold_();
        sink.tableCaption_();

        sink.tableRow();

        sink.tableCell();
        iconError( sink );
        sink.tableCell_();
        sink.tableCell();
        sink.text( getI18nString( locale, "legend.different" ) );
        sink.tableCell_();

        sink.tableRow_();

        sink.table_();
    }

    /**
     * Generate the statistic table
     * 
     * @param locale
     * @param sink
     * @param dependencyMap
     */
    private void generateStats( Locale locale, Sink sink, DependencyAnalyzeResult result )
    {
        int depCount = result.getDependencyCount();

        int artifactCount = result.getArtifactCount();
        int snapshotCount = result.getSnapshotCount();
        int conflictingCount = result.getConflictingCount();

        int convergence = (int) ( ( (double) depCount / (double) artifactCount ) * PERCENTAGE );

        // Create report
        sink.table();
        sink.tableCaption();
        sink.bold();
        sink.text( getI18nString( locale, "stats.caption" ) );
        sink.bold_();
        sink.tableCaption_();

        if ( isReactorBuild() )
        {
            sink.tableRow();
            sink.tableHeaderCell();
            sink.text( getI18nString( locale, "stats.subprojects" ) );
            sink.tableHeaderCell_();
            sink.tableCell();
            sink.text( String.valueOf( reactorProjects.size() ) );
            sink.tableCell_();
            sink.tableRow_();
        }

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( getI18nString( locale, "stats.dependencies" ) );
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text( String.valueOf( depCount ) );
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( getI18nString( locale, "stats.artifacts" ) );
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text( String.valueOf( artifactCount ) );
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( getI18nString( locale, "stats.conflicting" ) );
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text( String.valueOf( conflictingCount ) );
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( getI18nString( locale, "stats.snapshots" ) );
        sink.tableHeaderCell_();
        sink.tableCell();
        sink.text( String.valueOf( snapshotCount ) );
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( getI18nString( locale, "stats.convergence" ) );
        sink.tableHeaderCell_();
        sink.tableCell();
        if ( convergence < PERCENTAGE )
        {
            iconError( sink );
        }
        else
        {
            iconSuccess( sink );
        }
        sink.nonBreakingSpace();
        sink.bold();
        sink.text( String.valueOf( convergence ) + "%" );
        sink.bold_();
        sink.tableCell_();
        sink.tableRow_();

        sink.tableRow();
        sink.tableHeaderCell();
        sink.text( getI18nString( locale, "stats.readyrelease" ) );
        sink.tableHeaderCell_();
        sink.tableCell();
        if ( convergence >= PERCENTAGE && snapshotCount <= 0 )
        {
            iconSuccess( sink );
            sink.nonBreakingSpace();
            sink.bold();
            sink.text( getI18nString( locale, "stats.readyrelease.success" ) );
            sink.bold_();
        }
        else
        {
            iconError( sink );
            sink.nonBreakingSpace();
            sink.bold();
            sink.text( getI18nString( locale, "stats.readyrelease.error" ) );
            sink.bold_();
            if ( convergence < PERCENTAGE )
            {
                sink.lineBreak();
                sink.text( getI18nString( locale, "stats.readyrelease.error.convergence" ) );
            }
            if ( snapshotCount > 0 )
            {
                sink.lineBreak();
                sink.text( getI18nString( locale, "stats.readyrelease.error.snapshots" ) );
            }
        }
        sink.tableCell_();
        sink.tableRow_();

        sink.table_();
    }

    /**
     * Check to see if the specified dependency is among the reactor projects.
     * 
     * @param dependency The dependency to check
     * @return true if and only if the dependency is a reactor project
     */
    private boolean isReactorProject( Dependency dependency )
    {
        for ( MavenProject mavenProject : reactorProjects )
        {
            if ( mavenProject.getGroupId().equals( dependency.getGroupId() )
                && mavenProject.getArtifactId().equals( dependency.getArtifactId() ) )
            {
                if ( getLog().isDebugEnabled() )
                {
                    getLog().debug( dependency + " is a reactor project" );
                }
                return true;
            }
        }
        return false;
    }

    private boolean isReactorBuild()
    {
        return this.reactorProjects.size() > 1;
    }

    private static void link( Sink sink, String url )
    {
        if ( StringUtils.isNotEmpty( url ) )
        {
            sink.link( url );
        }
    }

    private static void link_( Sink sink, String url )
    {
        if ( StringUtils.isNotEmpty( url ) )
        {
            sink.link_();
        }
    }

    private void iconSuccess( Sink sink )
    {
        sink.figure();
        sink.figureCaption();
        sink.text( "success" );
        sink.figureCaption_();
        sink.figureGraphics( "images/icon_success_sml.gif" );
        sink.figure_();
    }

    private void iconError( Sink sink )
    {
        sink.figure();
        sink.figureCaption();
        sink.text( "error" );
        sink.figureCaption_();
        sink.figureGraphics( "images/icon_error_sml.gif" );
        sink.figure_();
    }

    /**
     * Produce a Map of relationships between dependencies (its groupId:artifactId) and reactor projects. This is the
     * structure of the Map:
     * 
     * <pre>
     * +--------------------+----------------------------------+
     * | key                | value                            |
     * +--------------------+----------------------------------+
     * | groupId:artifactId | A List of ReverseDependencyLinks |
     * | of a dependency    | which each look like this:       |
     * |                    | +------------+-----------------+ |
     * |                    | | dependency | reactor project | |
     * |                    | +------------+-----------------+ |
     * +--------------------+----------------------------------+
     * </pre>
     * 
     * @return A Map of relationships between dependencies and reactor projects
     * @throws MavenReportException
     */
    private Map<String, List<ReverseDependencyLink>> getDependencyMap()
        throws MavenReportException
    {
        Map<String, List<ReverseDependencyLink>> dependencyMap = new TreeMap<String, List<ReverseDependencyLink>>();

        for ( MavenProject reactorProject : reactorProjects )
        {
            DependencyNode node = getNode( project );

            DependencyVersionMap visitor = new DependencyVersionMap();
            visitor.setUniqueVersions( true );

            node.accept( visitor );

            for ( List<DependencyNode> nodes : visitor.getConflictedVersionNumbers() )
            {
                DependencyNode dependencyNode = nodes.get( 0 );

                String key =
                    dependencyNode.getArtifact().getGroupId() + ":" + dependencyNode.getArtifact().getArtifactId();

                List<ReverseDependencyLink> dependencyList = dependencyMap.get( key );
                if ( dependencyList == null )
                {
                    dependencyList = new ArrayList<ReverseDependencyLink>();
                }

                dependencyList.add( new ReverseDependencyLink( toDependency( dependencyNode.getArtifact() ),
                                                               reactorProject, dependencyNode ) );

                for ( DependencyNode workNode : nodes.subList( 1, nodes.size() ) )
                {
                    dependencyList.add( new ReverseDependencyLink( toDependency( workNode.getArtifact() ),
                                                                   reactorProject, workNode ) );
                }

                dependencyMap.put( key, dependencyList );
            }

            Set<Artifact> artifacts = getAllDescendants( node );

            for ( Artifact art : artifacts )
            {
                String key = art.getGroupId() + ":" + art.getArtifactId();

                List<ReverseDependencyLink> reverseDepependencies = dependencyMap.get( key );
                if ( reverseDepependencies == null )
                {
                    reverseDepependencies = new ArrayList<ReverseDependencyLink>();
                }

                if ( !containsDependency( reverseDepependencies, art ) )
                {
                    reverseDepependencies.add( new ReverseDependencyLink( toDependency( art ), reactorProject, null ) );
                }

                dependencyMap.put( key, reverseDepependencies );
            }

        }

        return dependencyMap;
    }

    /**
     * Convert Artifact to Dependency
     * 
     * @param artifact
     * @return Dependency object
     */
    private Dependency toDependency( Artifact artifact )
    {
        Dependency dependency = new Dependency();
        dependency.setGroupId( artifact.getGroupId() );
        dependency.setArtifactId( artifact.getArtifactId() );
        dependency.setVersion( artifact.getVersion() );
        dependency.setClassifier( artifact.getClassifier() );
        dependency.setScope( artifact.getScope() );

        return dependency;
    }

    /**
     * To check whether dependency list contains a given artifact.
     * 
     * @param reverseDependencies
     * @param art
     * @return contains:true; Not contains:false;
     */
    private boolean containsDependency( List<ReverseDependencyLink> reverseDependencies, Artifact art )
    {

        for ( ReverseDependencyLink revDependency : reverseDependencies )
        {
            Dependency dep = revDependency.getDependency();
            if ( dep.getGroupId().equals( art.getGroupId() ) && dep.getArtifactId().equals( art.getArtifactId() )
                && dep.getVersion().equals( art.getVersion() ) )
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Get root node of dependency tree for a given project
     * 
     * @param project
     * @return root node of dependency tree
     * @throws MavenReportException
     */
    private DependencyNode getNode( MavenProject project )
        throws MavenReportException
    {
        try
        {
            DependencyNode node =
                (DependencyNode) dependencyTreeBuilder.buildDependencyTree( project, localRepository, factory,
                                                                            metadataSource, filter, collector );

            return node;
        }
        catch ( DependencyTreeBuilderException e )
        {
            throw new MavenReportException( "Could not build dependency tree " + e.getLocalizedMessage(), e );
        }
    }

    /**
     * Get full name for a given artifact. {groupId}:{artifactId}:{version}
     * 
     * @param artifact
     * @return full name of a given artifact.
     */
    private static String getFullArtifactName( Artifact artifact )
    {
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
    }

    /**
     * Build tree sinks, help user to resolve conflicting issues easily.
     * 
     * @param node
     * @param sink
     */
    private static void buildTreeSink( ReverseDependencyLink rdl, Sink sink, boolean isReactorBuild )
    {
        DependencyNode node = rdl.getDependencyNode();

        if ( node == null )
        {
            return;
        }

        List<String> loc = new ArrayList<String>();
        DependencyNode currentNode = node;
        while ( currentNode != null )
        {
            if ( currentNode.getParent() == null )
            {
                break;
            }

            loc.add( getFullArtifactName( currentNode.getArtifact() ) );
            currentNode = currentNode.getParent();
        }

        Collections.reverse( loc );

        for ( String locElement : loc )
        {
            int j = 0;
            int idx = loc.indexOf( locElement );
            while ( j <= idx )
            {
                sink.nonBreakingSpace();
                sink.nonBreakingSpace();
                sink.nonBreakingSpace();
                sink.nonBreakingSpace();
                j++;
            }

            if ( idx == loc.size() - 1 )
            {
                sink.text( "\\-" + locElement );
            }
            else
            {
                sink.text( "+-" + locElement );
            }

            sink.lineBreak();
        }

    }

    /**
     * Get all descendants nodes for a given dependency node.
     * 
     * @param node
     * @return set of descendants artifacts.
     */
    private Set<Artifact> getAllDescendants( DependencyNode node )
    {
        Set<Artifact> children = null;
        if ( node.getChildren() != null )
        {
            children = new HashSet<Artifact>();
            for ( DependencyNode depNode : node.getChildren() )
            {
                children.add( depNode.getArtifact() );
                Set<Artifact> subNodes = getAllDescendants( depNode );
                if ( subNodes != null )
                {
                    children.addAll( subNodes );
                }
            }
        }
        return children;
    }

    /**
     * Internal object
     */
    private static class ReverseDependencyLink
    {
        private Dependency dependency;

        protected MavenProject project;

        private DependencyNode node;

        ReverseDependencyLink( Dependency dependency, MavenProject project, DependencyNode node )
        {
            this.dependency = dependency;
            this.project = project;
            this.node = node;
        }

        public Dependency getDependency()
        {
            return dependency;
        }

        public MavenProject getProject()
        {
            return project;
        }

        public DependencyNode getDependencyNode()
        {
            return node;
        }

        @Override
        public String toString()
        {
            return project.getId();
        }
    }

    /**
     * Internal ReverseDependencyLink comparator
     */
    static class ReverseDependencyLinkComparator
        implements Comparator<ReverseDependencyLink>
    {
        /** {@inheritDoc} */
        public int compare( ReverseDependencyLink p1, ReverseDependencyLink p2 )
        {
            return p1.getProject().getId().compareTo( p2.getProject().getId() );
        }
    }

    /**
     * Internal object
     */
    private class DependencyAnalyzeResult
    {
        Map<String, List<ReverseDependencyLink>> all;

        List<ReverseDependencyLink> snapshots;

        Map<String, List<ReverseDependencyLink>> conflicting;

        public void setAll( Map<String, List<ReverseDependencyLink>> all )
        {
            this.all = all;
        }

        public List<ReverseDependencyLink> getSnapshots()
        {
            return snapshots;
        }

        public void setSnapshots( List<ReverseDependencyLink> snapshots )
        {
            this.snapshots = snapshots;
        }

        public Map<String, List<ReverseDependencyLink>> getConflicting()
        {
            return conflicting;
        }

        public void setConflicting( Map<String, List<ReverseDependencyLink>> conflicting )
        {
            this.conflicting = conflicting;
        }

        public int getDependencyCount()
        {
            return all.size();
        }

        public int getSnapshotCount()
        {
            return this.snapshots.size();
        }

        public int getConflictingCount()
        {
            return this.conflicting.size();
        }

        public int getArtifactCount()
        {
            int artifactCount = 0;
            for ( List<ReverseDependencyLink> depList : this.all.values() )
            {
                Map<String, List<ReverseDependencyLink>> artifactMap = getSortedUniqueArtifactMap( depList );
                artifactCount += artifactMap.size();
            }

            return artifactCount;
        }
    }

}
