/**
 * Copyright 2007-2012 Arthur Blake
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.log4jdbc.sql.jdbcapi;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import net.sf.log4jdbc.Properties;
import net.sf.log4jdbc.log.SpyLogDelegator;
import net.sf.log4jdbc.log.SpyLogFactory;
import net.sf.log4jdbc.sql.Spy;
import net.sf.log4jdbc.sql.rdbmsspecifics.Db2RdbmsSpecifics;
import net.sf.log4jdbc.sql.rdbmsspecifics.MySqlRdbmsSpecifics;
import net.sf.log4jdbc.sql.rdbmsspecifics.OracleRdbmsSpecifics;
import net.sf.log4jdbc.sql.rdbmsspecifics.RdbmsSpecifics;
import net.sf.log4jdbc.sql.rdbmsspecifics.SqlServerRdbmsSpecifics;


/**
 * A JDBC driver which is a facade that delegates to one or more real underlying
 * JDBC drivers.  The driver will spy on any other JDBC driver that is loaded,
 * simply by prepending <code>jdbc:log4</code> to the normal jdbc driver URL
 * used by any other JDBC driver. The driver, by default, also loads several
 * well known drivers at class load time, so that this driver can be
 * "dropped in" to any Java program that uses these drivers without making any
 * code changes.
 * <p/>
 * The well known driver classes that are loaded are:
 * <p/>
 * <p/>
 * <code>
 * <ul>
 * <li>oracle.jdbc.driver.OracleDriver</li>
 * <li>com.sybase.jdbc2.jdbc.SybDriver</li>
 * <li>net.sourceforge.jtds.jdbc.Driver</li>
 * <li>com.microsoft.jdbc.sqlserver.SQLServerDriver</li>
 * <li>com.microsoft.sqlserver.jdbc.SQLServerDriver</li>
 * <li>weblogic.jdbc.sqlserver.SQLServerDriver</li>
 * <li>com.informix.jdbc.IfxDriver</li>
 * <li>org.apache.derby.jdbc.ClientDriver</li>
 * <li>org.apache.derby.jdbc.EmbeddedDriver</li>
 * <li>com.mysql.jdbc.Driver</li>
 * <li>org.postgresql.Driver</li>
 * <li>org.hsqldb.jdbcDriver</li>
 * <li>org.h2.Driver</li>
 * </ul>
 * </code>
 * <p/>
 * <p/>
 * Additional drivers can be set via a property: <b>log4jdbc.drivers</b>
 * This can be either a single driver class name or a list of comma separated
 * driver class names.
 * <p/>
 * The autoloading behavior can be disabled by setting a property:
 * <b>log4jdbc.auto.load.popular.drivers</b> to false.  If that is done, then
 * the only drivers that log4jdbc will attempt to load are the ones specified
 * in <b>log4jdbc.drivers</b>.
 * <p/>
 * If any of the above driver classes cannot be loaded, the driver continues on
 * without failing.
 * <p/>
 * Note that the <code>getMajorVersion</code>, <code>getMinorVersion</code> and
 * <code>jdbcCompliant</code> method calls attempt to delegate to the last
 * underlying driver requested through any other call that accepts a JDBC URL.
 * <p/>
 * This can cause unexpected behavior in certain circumstances.  For example,
 * if one of these 3 methods is called before any underlying driver has been
 * established, then they will return default values that might not be correct
 * in all situations.  Similarly, if this spy driver is used to spy on more than
 * one underlying driver concurrently, the values returned by these 3 method
 * calls may change depending on what the last underlying driver used was at the
 * time.  This will not usually be a problem, since the driver is retrieved by
 * it's URL from the DriverManager in the first place (thus establishing an
 * underlying real driver), and in most applications their is only one database.
 * 
 * <h3>Modifications for log4j2: </h3>
 * <ul>
 * <li>The initialization of all properties have been delegated to the class 
 * {@link net.sf.log4jdbc.log4j2.Properties}.
 * <li>Modification of the method <code>connect(String, Properties)</code> 
 * in order to compute the time taken to open a connection to the database. 
 * Constructors of <code>ConnectionSpy</code> have been modified accordingly.
 * </ul>
 *
 * @author Arthur Blake
 * @author Frederic Bastian
 * @author Mathieu Seppey
 */
public class DriverSpy implements Driver
{
	/**
	 * The last actual, underlying driver that was requested via a URL.
	 */
	private Driver lastUnderlyingDriverRequested;

	/**
	 * Maps driver class names to RdbmsSpecifics objects for each kind of
	 * database.
	 */
	private final static Map<String, RdbmsSpecifics> rdbmsSpecifics;

	/**
	 * Default <code>RdbmsSpecifics</code>.
	 */
	static final RdbmsSpecifics defaultRdbmsSpecifics = new RdbmsSpecifics();

	/**
	 * A <code>SpyLogDelegator</code> used here for logs internal to log4jdbc 
	 * (see <code>debug(String)</code> method of <code>SpyLogDelegator</code>).
	 */
	static final SpyLogDelegator log = SpyLogFactory.getSpyLogDelegator();
	
	/**
	 * A <code>String</code> representing the prefix of URL 
	 * to use log4jdbc. 
	 */
	static final private String log4jdbcUrlPrefix = "jdbc:log4";

	/**
	 * Default constructor.
	 */
	public DriverSpy()
	{
		
	}

	/**
	 * Static initializer.
	 */
	static
	{
		log.debug("DriverSpy initialization...");
		
		// The Set of drivers that the log4jdbc driver will preload at instantiation
		// time.  The driver can spy on any driver type, it's just a little bit
		// easier to configure log4jdbc if it's one of these types!
		Set<String> subDrivers = new TreeSet<String>();

		if (Properties.isAutoLoadPopularDrivers()) {
			subDrivers.add("oracle.jdbc.driver.OracleDriver");
			subDrivers.add("oracle.jdbc.OracleDriver");
			subDrivers.add("com.sybase.jdbc2.jdbc.SybDriver");
			subDrivers.add("net.sourceforge.jtds.jdbc.Driver");
			subDrivers.add("com.ibm.db2.jcc.DB2Driver");
			subDrivers.add("COM.ibm.db2.jdbc.app.DB2Driver");
        		subDrivers.add("COM.ibm.db2.jdbc.net.DB2Driver");
			
			// MS driver for Sql Server 2000
			subDrivers.add("com.microsoft.jdbc.sqlserver.SQLServerDriver");

			// MS driver for Sql Server 2005
			subDrivers.add("com.microsoft.sqlserver.jdbc.SQLServerDriver");

			subDrivers.add("weblogic.jdbc.sqlserver.SQLServerDriver");
			subDrivers.add("com.informix.jdbc.IfxDriver");
			subDrivers.add("org.apache.derby.jdbc.ClientDriver");
			subDrivers.add("org.apache.derby.jdbc.EmbeddedDriver");
			subDrivers.add("com.mysql.jdbc.Driver");
			subDrivers.add("org.postgresql.Driver");
			subDrivers.add("org.hsqldb.jdbcDriver");
			subDrivers.add("org.h2.Driver");
		}

		// look for additional driver specified in properties
		subDrivers.addAll(Properties.getAdditionalDrivers());

		try {
			DriverManager.registerDriver(new DriverSpy());
		} catch (SQLException s) {
			// this exception should never be thrown, JDBC just defines it
			// for completeness
			throw (RuntimeException) new RuntimeException
			("could not register log4jdbc driver!").initCause(s);
		}

		// instantiate all the supported drivers and remove
		// those not found
		String driverClass;
		for (Iterator<String> i = subDrivers.iterator(); i.hasNext();) {
			driverClass = i.next();
			try {
				Class.forName(driverClass);
				log.debug("  FOUND DRIVER " + driverClass);
			} catch (Throwable c) {
				i.remove();
			}
		}

		if (subDrivers.size() == 0) {
			log.debug("WARNING!  " +
					"log4jdbc couldn't find any underlying jdbc drivers.");
		}

		SqlServerRdbmsSpecifics sqlServer = new SqlServerRdbmsSpecifics();
		OracleRdbmsSpecifics oracle = new OracleRdbmsSpecifics();
		MySqlRdbmsSpecifics mySql = new MySqlRdbmsSpecifics();
		Db2RdbmsSpecifics db2 = new Db2RdbmsSpecifics();

		/** create lookup Map for specific rdbms formatters */
		rdbmsSpecifics = new HashMap<String, RdbmsSpecifics>();
		rdbmsSpecifics.put("oracle.jdbc.driver.OracleDriver", oracle);
		rdbmsSpecifics.put("oracle.jdbc.OracleDriver", oracle);
		rdbmsSpecifics.put("net.sourceforge.jtds.jdbc.Driver", sqlServer);
		rdbmsSpecifics.put("com.microsoft.jdbc.sqlserver.SQLServerDriver",
				sqlServer);
		rdbmsSpecifics.put("weblogic.jdbc.sqlserver.SQLServerDriver", sqlServer);
		rdbmsSpecifics.put("com.mysql.jdbc.Driver", mySql);
       	 	rdbmsSpecifics.put("com.ibm.db2.jcc.DB2Driver", db2);
        	rdbmsSpecifics.put("COM.ibm.db2.jdbc.app.DB2Driver", db2);
        	rdbmsSpecifics.put("COM.ibm.db2.jdbc.net.DB2Driver", db2);

		log.debug("DriverSpy initialization done.");
	}

	/**
	 * Get the RdbmsSpecifics object for a given Connection.
	 *
	 * @param conn JDBC connection to get RdbmsSpecifics for.
	 * @return RdbmsSpecifics for the given connection.
	 */
	static RdbmsSpecifics getRdbmsSpecifics(Connection conn)
	{
		String driverName = "";
		try {
			DatabaseMetaData dbm = conn.getMetaData();
			driverName = dbm.getDriverName();
		} catch (SQLException s) {
			// silently fail
		}

		log.debug("driver name is " + driverName);

		RdbmsSpecifics r = rdbmsSpecifics.get(driverName);

		if (r == null) {
			return defaultRdbmsSpecifics;
		} 
		return r;
	}

	/**
	 * Get the major version of the driver.  This call will be delegated to the
	 * underlying driver that is being spied upon (if there is no underlying
	 * driver found, then 1 will be returned.)
	 *
	 * @return the major version of the JDBC driver.
	 */
	@Override
	public int getMajorVersion()
	{
		if (lastUnderlyingDriverRequested == null) {
			return 1;
		} 
		return lastUnderlyingDriverRequested.getMajorVersion();
	}

	/**
	 * Get the minor version of the driver.  This call will be delegated to the
	 * underlying driver that is being spied upon (if there is no underlying
	 * driver found, then 0 will be returned.)
	 *
	 * @return the minor version of the JDBC driver.
	 */
	@Override
	public int getMinorVersion()
	{
		if (lastUnderlyingDriverRequested == null) {
			return 0;
		}
		return lastUnderlyingDriverRequested.getMinorVersion();
	}

	/**
	 * Report whether the underlying driver is JDBC compliant.  If there is no
	 * underlying driver, false will be returned, because the driver cannot
	 * actually do any work without an underlying driver.
	 *
	 * @return <code>true</code> if the underlying driver is JDBC Compliant;
	 *         <code>false</code> otherwise.
	 */
	@Override
	public boolean jdbcCompliant()
	{
		return lastUnderlyingDriverRequested != null &&
			   lastUnderlyingDriverRequested.jdbcCompliant();
	}

	/**
	 * Returns true if this is a <code>jdbc:log4</code> URL and if the URL is for
	 * an underlying driver that this DriverSpy can spy on.
	 *
	 * @param url JDBC URL.
	 *
	 * @return true if this Driver can handle the URL.
	 *
	 * @throws SQLException if a database access error occurs
	 */
	@Override
	public boolean acceptsURL(String url) throws SQLException
	{
		Driver d = getUnderlyingDriver(url);
		if (d != null) {
			lastUnderlyingDriverRequested = d;
			return true;
		}
		return false;
	}

	/**
	 * Given a <code>jdbc:log4</code> type URL, find the underlying real driver
	 * that accepts the URL.
	 *
	 * @param url JDBC connection URL.
	 *
	 * @return Underlying driver for the given URL. Null is returned if the URL is
	 *         not a <code>jdbc:log4</code> type URL or there is no underlying
	 *         driver that accepts the URL.
	 *
	 * @throws SQLException if a database access error occurs.
	 */
	private Driver getUnderlyingDriver(String url) throws SQLException
	{
		if (url.startsWith(log4jdbcUrlPrefix)) {
			url = this.getRealUrl(url);

			Enumeration<Driver> e = DriverManager.getDrivers();

			Driver d;
			while (e.hasMoreElements()) {
				d = e.nextElement();

				if (d.acceptsURL(url)) {
					return d;
				}
			}
		}
		return null;
	}
	
	/**
	 * Get the actual URL that the real driver expects 
	 * (strip off <code>#log4jdbcUrlPrefix</code> from <code>url</code>).
	 * 
	 * @param url 	A <code>String</code> corresponding to a JDBC url for log4jdbc. 
	 * @return 		A <code>String</code> representing url 
	 * 				with <code>#log4jdbcUrlPrefix</code> stripped off. 
	 */
	private String getRealUrl(String url)
	{
		return url.substring(log4jdbcUrlPrefix.length());
	}

	/**
	 * Get a Connection to the database from the underlying driver that this
	 * DriverSpy is spying on.  If logging is not enabled, an actual Connection to
	 * the database returned.  If logging is enabled, a ConnectionSpy object which
	 * wraps the real Connection is returned.
	 *
	 * @param url  JDBC connection URL
	 * .
	 * @param info a list of arbitrary string tag/value pairs as
	 *             connection arguments. Normally at least a "user" and
	 *             "password" property should be included.
	 *
	 * @return     a <code>Connection</code> object that represents a
	 *             connection to the URL.
	 *
	 * @throws SQLException if a database access error occurs
	 */
	@Override
	public Connection connect(String url, java.util.Properties info) throws SQLException
	{
		Driver d = getUnderlyingDriver(url);
		if (d == null) {
			return null;
		}

		// get actual URL that the real driver expects
		// (strip off <code>#log4jdbcUrlPrefix</code> from url)
		url = this.getRealUrl(url);

		lastUnderlyingDriverRequested = d;
		long tstart = System.currentTimeMillis();
		Connection c = d.connect(url, info);

		if (c == null) {
			throw new SQLException("invalid or unknown driver url: " + url);
		}
		if (log.isJdbcLoggingEnabled()) {
			ConnectionSpy cspy = new ConnectionSpy(c, System.currentTimeMillis() - tstart, log);
			RdbmsSpecifics r = null;
			String dclass = d.getClass().getName();
			if (dclass != null && dclass.length() > 0)
			{
				r = rdbmsSpecifics.get(dclass);
			}

			if (r == null)
			{
				r = defaultRdbmsSpecifics;
			}
			cspy.setRdbmsSpecifics(r);
			return cspy;
		}
		return c;
	}

	/**
	 * Gets information about the possible properties for the underlying driver.
	 *
	 * @param url  the URL of the database to which to connect
	 *
	 * @param info a proposed list of tag/value pairs that will be sent on
	 *             connect open
	 * @return     an array of <code>DriverPropertyInfo</code> objects describing
	 *             possible properties.  This array may be an empty array if no
	 *             properties are required.
	 *
	 * @throws SQLException if a database access error occurs
	 */
	@Override
	public DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties info)
			throws SQLException
	{
		Driver d = getUnderlyingDriver(url);
		if (d == null)
		{
			return new DriverPropertyInfo[0];
		}

		lastUnderlyingDriverRequested = d;
		return d.getPropertyInfo(url, info);
	}

	protected void reportException(String methodCall, SQLException exception)
	{
		log.exceptionOccured((Spy) this, methodCall, exception, null, -1L);
	}  

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException
	{
		String methodCall = "getParentLogger()";
		try
		{
			return lastUnderlyingDriverRequested.getParentLogger();  
		}
		catch (SQLFeatureNotSupportedException s)
		{
			reportException(methodCall,s);
			throw s;      
		}
	}
}
