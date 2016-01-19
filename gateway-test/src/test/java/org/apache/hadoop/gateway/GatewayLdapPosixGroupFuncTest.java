/**
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.gateway;

import com.mycila.xmltool.XMLDoc;
import com.mycila.xmltool.XMLTag;
import org.apache.directory.server.protocol.shared.transport.TcpTransport;
import org.apache.hadoop.gateway.config.GatewayConfig;
import org.apache.hadoop.gateway.security.ldap.SimpleLdapDirectoryServer;
import org.apache.hadoop.gateway.services.DefaultGatewayServices;
import org.apache.hadoop.gateway.services.GatewayServices;
import org.apache.hadoop.gateway.services.ServiceLifecycleException;
import org.apache.hadoop.gateway.services.security.AliasService;
import org.apache.hadoop.test.category.FunctionalTests;
import org.apache.http.HttpStatus;
import org.apache.log4j.Appender;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static com.jayway.restassured.RestAssured.given;
import static org.apache.hadoop.test.TestUtils.LOG_ENTER;
import static org.apache.hadoop.test.TestUtils.LOG_EXIT;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Functional test to verify : looking up ldap groups from directory 
 * and using them in acl authorization checks
 *
 */
@Category(FunctionalTests.class)
public class GatewayLdapPosixGroupFuncTest {

  private static final long SHORT_TIMEOUT = 2000L;
  private static final long MEDIUM_TIMEOUT = 5 * 1000L;

  private static Class RESOURCE_BASE_CLASS = GatewayLdapPosixGroupFuncTest.class;
  private static Logger LOG = LoggerFactory.getLogger( GatewayLdapPosixGroupFuncTest.class );

  public static Enumeration<Appender> appenders;
  public static GatewayConfig config;
  public static GatewayServer gateway;
  public static String gatewayUrl;
  public static String clusterUrl;
  public static SimpleLdapDirectoryServer ldap;
  public static TcpTransport ldapTransport;

  @BeforeClass
  public static void setupSuite() throws Exception {
    LOG_ENTER();
    //appenders = NoOpAppender.setUp();
    int port = setupLdap();
    setupGateway(port);
    LOG_EXIT();
  }

  @AfterClass
  public static void cleanupSuite() throws Exception {
    LOG_ENTER();
    gateway.stop();
    ldap.stop( true );
    //FileUtils.deleteQuietly( new File( config.getGatewayHomeDir() ) );
    //NoOpAppender.tearDown( appenders );
    LOG_EXIT();
  }

  public static int setupLdap() throws Exception {
    URL usersUrl = getResourceUrl( "users.ldif" );
    int port = findFreePort();
    ldapTransport = new TcpTransport( port );
    ldap = new SimpleLdapDirectoryServer( "dc=hadoop,dc=apache,dc=org", new File( usersUrl.toURI() ), ldapTransport );
    ldap.start();
    LOG.info( "LDAP port = " + ldapTransport.getPort() );
    return port;
  }

  public static void setupGateway(int ldapPort) throws Exception {

    File targetDir = new File( System.getProperty( "user.dir" ), "target" );
    File gatewayDir = new File( targetDir, "gateway-home-" + UUID.randomUUID() );
    gatewayDir.mkdirs();

    GatewayTestConfig testConfig = new GatewayTestConfig();
    config = testConfig;
    testConfig.setGatewayHomeDir( gatewayDir.getAbsolutePath() );

    File topoDir = new File( testConfig.getGatewayTopologyDir() );
    topoDir.mkdirs();

    File deployDir = new File( testConfig.getGatewayDeploymentDir() );
    deployDir.mkdirs();

    File descriptor = new File( topoDir, "test-cluster.xml" );
    FileOutputStream stream = new FileOutputStream( descriptor );
    createTopology(ldapPort).toStream( stream );
    stream.close();

    DefaultGatewayServices srvcs = new DefaultGatewayServices();
    Map<String,String> options = new HashMap<String,String>();
    options.put( "persist-master", "true" );
    options.put( "master", "hadoop" );

    try {
      srvcs.init( testConfig, options );
    } catch ( ServiceLifecycleException e ) {
      e.printStackTrace(); // I18N not required.
    }

    gateway = GatewayServer.startGateway( testConfig, srvcs );
    MatcherAssert.assertThat( "Failed to start gateway.", gateway, notNullValue() );

    LOG.info( "Gateway port = " + gateway.getAddresses()[ 0 ].getPort() );

    gatewayUrl = "http://localhost:" + gateway.getAddresses()[0].getPort() + "/" + config.getGatewayPath();
    clusterUrl = gatewayUrl + "/test-cluster";

    GatewayServices services = GatewayServer.getGatewayServices();
    AliasService aliasService = (AliasService)services.getService(GatewayServices.ALIAS_SERVICE);
    aliasService.addAliasForCluster("test-cluster", "ldcSystemPassword", "guest-password");

    char[] password1 = aliasService.getPasswordFromAliasForCluster( "test-cluster", "ldcSystemPassword");

    descriptor = new File( topoDir, "test-cluster.xml" );
    stream = new FileOutputStream( descriptor );
    createTopology(ldapPort).toStream( stream );
    stream.close();

    try {
      Thread.sleep(5000);
    } catch (Exception e) {

    }
  }

  private static XMLTag createTopology(int ldapPort) {
    XMLTag xml = XMLDoc.newDocument( true )
        .addRoot( "topology" )
        .addTag( "gateway" )

        .addTag( "provider" )
        .addTag( "role" ).addText( "authentication" )
        .addTag( "name" ).addText( "ShiroProvider" )
        .addTag( "enabled" ).addText( "true" )
        .addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm" )
        .addTag( "value" ).addText( "org.apache.hadoop.gateway.shirorealm.KnoxLdapRealm" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapGroupContextFactory" )
        .addTag( "value" ).addText( "org.apache.hadoop.gateway.shirorealm.KnoxLdapContextFactory" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory" )
        .addTag( "value" ).addText( "$ldapGroupContextFactory" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory.authenticationMechanism" )
        .addTag( "value" ).addText( "simple" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory.url" )
        .addTag( "value" ).addText( "ldap://localhost:" + ldapPort )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.userDnTemplate" )
        .addTag( "value" ).addText( "uid={0},ou=people,dc=hadoop,dc=apache,dc=org" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.authorizationEnabled" )
        .addTag( "value" ).addText( "true" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory.systemAuthenticationMechanism" )
        .addTag( "value" ).addText( "simple" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.searchBase" )
        .addTag( "value" ).addText( "ou=groups,dc=hadoop,dc=apache,dc=org" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.groupObjectClass" )
        .addTag( "value" ).addText( "posixGroup" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.memberAttribute" )
        .addTag( "value" ).addText( "memberUid" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.memberAttributeValueTemplate" )
        .addTag( "value" ).addText( "uid={0}" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory.clusterName" )
        .addTag( "value" ).addText( "test-cluster" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory.systemUsername" )
        .addTag( "value" ).addText( "uid=guest,ou=people,dc=hadoop,dc=apache,dc=org" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "main.ldapRealm.contextFactory.systemPassword" )
        .addTag( "value" ).addText( "S{ALIAS=ldcSystemPassword}" )
        .gotoParent().addTag( "param" )
        .addTag( "name" ).addText( "urls./**" )
        .addTag( "value" ).addText( "authcBasic" )

        .gotoParent().gotoParent().addTag( "provider" )
        .addTag( "role" ).addText( "authorization" )
        .addTag( "name" ).addText( "AclsAuthz" )
        .addTag( "enabled" ).addText( "true" )
        .addTag( "param" )
        .addTag( "name" ).addText( "test-service-role.acl" )
        .addTag( "value" ).addText( "*;analyst;*" )

        .gotoParent().gotoParent().addTag( "provider" )
        .addTag( "role" ).addText( "identity-assertion" )
        .addTag( "enabled" ).addText( "true" )
        .addTag( "name" ).addText( "Default" ).gotoParent()

        .gotoRoot()
        .addTag( "service" )
        .addTag( "role" ).addText( "test-service-role" )
        .gotoRoot();

    return xml;
  }

  private static int findFreePort() throws IOException {
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }

  public static InputStream getResourceStream( String resource ) throws IOException {
    return getResourceUrl( resource ).openStream();
  }

  public static URL getResourceUrl( String resource ) {
    URL url = ClassLoader.getSystemResource( getResourceName( resource ) );
    assertThat( "Failed to find test resource " + resource, url, Matchers.notNullValue() );
    return url;
  }

  public static String getResourceName( String resource ) {
    return getResourceBaseName() + resource;
  }

  public static String getResourceBaseName() {
    return RESOURCE_BASE_CLASS.getName().replaceAll( "\\.", "/" ) + "/";
  }

  @Ignore
  // @Test
  public void waitForManualTesting() throws IOException {
    System.in.read();
  }

  @Test( timeout = MEDIUM_TIMEOUT )
  public void testGroupMember() throws ClassNotFoundException, Exception {
    LOG_ENTER();
    String username = "sam";
    String password = "sam-password";
    String serviceUrl =  clusterUrl + "/test-service-path/test-service-resource";
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_OK )
        .contentType( "text/plain" )
        .body( is( "test-service-response" ) )
        .when().get( serviceUrl );
    LOG_EXIT();
  }

  @Test( timeout = MEDIUM_TIMEOUT )
  public void testNonGroupMember() throws ClassNotFoundException {
    LOG_ENTER();
    String username = "guest";
    String password = "guest-password";
    String serviceUrl =  clusterUrl + "/test-service-path/test-service-resource";
    given()
        //.log().all()
        .auth().preemptive().basic( username, password )
        .expect()
        //.log().all()
        .statusCode( HttpStatus.SC_FORBIDDEN )
        .when().get( serviceUrl );
    LOG_EXIT();
  }

}
