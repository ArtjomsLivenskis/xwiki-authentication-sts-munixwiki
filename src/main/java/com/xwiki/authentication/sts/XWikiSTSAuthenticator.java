/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xwiki.authentication.sts;

import com.xpn.xwiki.*;
import com.xpn.xwiki.doc.*;
import com.xpn.xwiki.objects.*;
import com.xpn.xwiki.user.api.XWikiUser;
import com.xpn.xwiki.user.impl.xwiki.XWikiAuthServiceImpl;
import com.xpn.xwiki.web.XWikiRequest;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import javax.servlet.http.HttpServletRequest;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opensaml.*;
import org.opensaml.xml.*;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.apache.commons.lang.StringEscapeUtils.escapeHtml;

/**
 * Authentication based on Trust Security Token Service. Some parameters can be
 * used to customized its behavior in xwiki.cfg.
 * Based on context - is showingLogin, can return XWiki User object according to
 * context parametr. Implements XWiki - authentication and have methods to show login
 * and everything this is making according the context. 
 * 
 * @version $Id$
 */
public class XWikiSTSAuthenticator extends XWikiAuthServiceImpl {
	/** 
	 * props - Props Variable - Holding method to load Certificate from file
	 */
	private static Log log = LogFactory.getLog(XWikiSTSAuthenticator.class);
	/**
	* Holds the mapping between HTTP header fields names and XWiki user
	*/
	private static Map<String, String> userMappings;
	/** 
	 * props - Props Variable - Holding method to load Certificate from file
	 */
	private static Props props = new Props();
	/**
	*  Error collector - collecting errors in a List. Converting to strings
	*/
	private STSErrorCollector errorCollector = new STSErrorCollector();
	
	 /**
     * showLogin - Makes appropriate url and sends request to the STS (Security Token Service)  
     * and gets response with xwiki methods.
     * 
     * @param context XWikiContext - context - to make request and show login
     * @throws XWikiException java.lang.Object extended by java.lang.Throwable </br> extended by java.lang.Exception extended by com.xpn.xwiki.XWikiException
     */
	@Override
	public void showLogin(XWikiContext context) throws XWikiException {
		log.trace("showLogin()");
		XWikiRequest request = context.getRequest();
		try {
			DefaultBootstrap.bootstrap();
		} catch (ConfigurationException e) {
			log.error("Failed to bootstrap sts module" + e);
			errorCollector.addError(new Throwable(
					"Failed to bootstrap sts module: ", e));
		}

		// STS provider URL
		String url = props.getAuthURL(context) + "?wa=wsignin1.0";
		// Request realm
		String wtrealm = props.getWtrealm(context);
		if (wtrealm != null && wtrealm != "")
			url += "&wtrealm=" + escapeHtml(wtrealm);
		// Request ID
		String wctx = props.getWctx(context);
		if (wctx != null && "1".equals(wctx)) {
			String randId = RandomStringUtils.randomAlphanumeric(42);
			log.debug("Request ID: " + randId);
			request.getSession().setAttribute("saml_id", randId);
			url += "&wctx=" + randId;
		}
		// Host is set manually, because XWiki is behind proxy server
		// and simple XWiki.getRequestURL(request) returns localhost
		String wreplyHost = props.getWreplyHost(context);
		String wreplyPage = props.getWreplyPage(context);
		String page = "/";
		if (wreplyHost != null && !"0".equals(wreplyHost)) {
			if ("1".equals(wreplyPage) || "shorten".equals(wreplyPage)) {
				page = request.getParameter("xredirect");
				if (page != null)
					log.trace("Got xrecdirect to: " + page);
				else
					page = XWiki.getRequestURL(request).getFile();
				if ("shorten".equals(wreplyPage)) {
					// change reply address if URL shortening is used
					page = page.replace("/xwiki/bin/view/", "/");
					page = page.replace("/WebHome", "/");
				}
				log.trace("Reply page: " + page);
			}
			url += "&wreply=" + escapeHtml(wreplyHost + page);
		}
		request.getSession().setAttribute("saml_url", wreplyHost + page);

		// Auth request time
		String wct = props.getWct(context);
		if (wct != null && "1".equals(wct)) {
			SimpleDateFormat dateFormatGmt = new SimpleDateFormat(
					"yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
			dateFormatGmt.setTimeZone(TimeZone.getTimeZone("GMT"));
			wct = dateFormatGmt.format(new Date());
			url += "&wct=" + wct;
		}
		// maximum age of authentication
		String wfresh = props.getWfresh(context);
		if (wfresh != null && wfresh != "" && Integer.parseInt(wfresh) > 0) {
			url += "&wfresh=" + wfresh;
		}
		// Send request to the STS service
		log.info("SAML STS request sent to " + url);

		try {
			context.getResponse().sendRedirect(url);
			context.setFinished(true);
		} catch (Exception e) {
			log.debug("Cannot call sendRedirect() after the response has been committed: "
					+ e);
		}

	}
	
	 /**
     * checkSTSResponse - Check Response of (Security Token Service)  
     * This method is trying to create document using XWikiContext context argument and checking is it done or not? 
     * @param context XWikiContext - XWikiContext to check is it have right data
     * @return boolen - true - if check - ok, false - fault to create doc from test data etc.
     * @throws XWikiException java.lang.Object extended by java.lang.Throwable </br> extended by java.lang.Exception extended by com.xpn.xwiki.XWikiException
	 * @throws ConfigurationException
	 */
	public boolean checkSTSResponse(XWikiContext context) throws XWikiException {
		// read from STSResponse
		log.trace("checkSTSResponse()");
		XWikiRequest request = context.getRequest();
		Map<String, String> attributes = new HashMap<String, String>();
		String authType = "";
		try {
			Enumeration<String> headerNames = request.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				log.trace(headerName + ": " + request.getHeader(headerName));
			}
			Enumeration<String> paramNames = request.getParameterNames();
			while (paramNames.hasMoreElements()) {
				String paramName = paramNames.nextElement();
				log.trace(paramName + ": " + request.getParameter(paramName));
			}
		} catch (Exception e) {
			log.error("Failed to read request headers or parameters: " + e);
			errorCollector.addError(new Throwable(
					"Failed to read request headers or parameters: ", e));
		}

		String stsResponse = request.getParameter("wresult");
		if (stsResponse == null) {
			log.debug("Didn't get wresult from request!");
			log.error(errorCollector.listErrors());
			errorCollector.clearErrorList();
			
			return false;
		}

		log.debug("\n***** STS Response: *****\n" + stsResponse + "\n*****");
		HttpServletRequest myRequest = context.getRequest()
				.getHttpServletRequest();
		try {
			log.debug("request.getParameter('wresult') is "
					+ myRequest.getParameter("wresult"));
			log.debug("request.getParameter('wct') is "
					+ myRequest.getParameter("wct"));
			log.debug("request.getParameter('wctx') is "
					+ myRequest.getParameter("wctx"));
			log.debug("request.getParameter('wa') is "
					+ myRequest.getParameter("wa"));
		} catch (Exception e) {
			log.error("Failed to read response request parameters" + e);
			errorCollector.addError(new Throwable(
					"Failed to read response request parameters: ", e));
		}

		// Get Context ID from the user session
		String prevId = (String) request.getSession().getAttribute("saml_id");
		String curId = myRequest.getParameter("wctx");
		String wctx = props.getWctx(context);
		// Check response and token
		if (wctx != null && "1".equals(wctx)) {
			try { // Check token validity
				if (!prevId.equals(curId)) {
					log.debug("Retrieved wctx parameter value doesn't match passed value. Passed: "
							+ prevId + " retrieved: " + curId);
					return false;
				}
				STSTokenValidator validator = new STSTokenValidator();
				validator.setSTSErrorCollector(errorCollector);
				validator.setContext(prevId);
				// Get parameters from Xwiki configuration
				validator.setIssuer(props.getIssuer(context));
				log.debug("props.getIssuer(context) "
						+ props.getIssuer(context));
				log.debug("checkSTSResponse(props.getIssuer(context)) "
						+ props.getIssuer(context));
				STSTokenValidator.setEntityId(props.getEntityId(context));
				validator.setIssuerDN(props.getIssuerDN(context));
				List<String> subjectDNs = new ArrayList<String>();
				subjectDNs.add(props.getSubjectDNs(context));
				validator.setSubjectDNs(subjectDNs);
				List<URI> audienceURIs = new ArrayList<URI>();
				audienceURIs.add(new URI(props.getAudienceURIs(context)));
				validator.setAudienceUris(audienceURIs);
				String wct = props.getWct(context);
				validator.setCertificate(props.getCertificate(context));
				// If time control is set, use time validation
				if (wct != null && "1".equals(wct)) {
					int maxClockSkew = Integer.parseInt(props
							.getWfresh(context)) * 60 * 1000;
					validator.setMaxClockSkew(maxClockSkew);
					validator.setValidateExpiration(true);
				} else
					validator.setValidateExpiration(false);
				List<STSClaim> claims = validator.validate(myRequest
						.getParameter("wresult"));
				log.trace("Token claims: " + claims);

			} catch (Exception e) {
				// as validator returns validation errors as exceptions
				// log them only in debug mode
				log.error("Failed to validate token\n" + e);
				errorCollector.addError(new Throwable(
						"Failed to validate token: ", e));
				return false;
			}

			try {
				DocumentBuilderFactory dbf = DocumentBuilderFactory
						.newInstance();
				DocumentBuilder db = dbf.newDocumentBuilder();
				Document doc = db.parse(new ByteArrayInputStream(stsResponse
						.getBytes("utf-8")));
				doc.getDocumentElement().normalize();
				log.debug("Root element:"
						+ doc.getDocumentElement().getNodeName());
				NodeList nodeList = doc.getElementsByTagName("saml:Attribute");
				log.debug("Node list length:" + nodeList.getLength());
				// Process all attributes
				for (int i = 0; i < nodeList.getLength(); i++) {
					Node node = nodeList.item(i);
					String attrName = node.getAttributes()
							.getNamedItem("AttributeName").getTextContent();
					String attrValue = node.getChildNodes().item(0)
							.getTextContent();
					// CAPITAL
					if ("CAPITAL".equals(props.getDataFormat(context)))
						attrValue = attrValue.toUpperCase();
					// Title
					if ("Title".equals(props.getDataFormat(context)))
						attrValue = attrValue.substring(0, 1).toUpperCase()
								+ ((attrValue.length() > 1) ? attrValue
										.substring(1).toLowerCase() : "");
					log.debug("Node:" + attrName + ", value:" + attrValue);
					attributes.put(attrName, attrValue);
				}
				// get authentication method (should be known for legal reasons)
				authType = doc
						.getElementsByTagName("saml:AuthenticationStatement")
						.item(0).getAttributes()
						.getNamedItem("AuthenticationMethod").getNodeValue();
			} catch (Exception e) {
				log.error("Failed reading STS response\n" + e);
				errorCollector.addError(new Throwable(
						"Failed reading STS response: ", e));
				return false;
			}
		} else {
			log.warn("Response and token were not validated! To validate, set xwiki.authentication.sts.wctx=1");
		}

		// let's map the data
		Map<String, String> userData = getExtendedInformation(attributes,
				context);
		String personID = attributes.get(props.getIdField(context));
		// set conventional person code format for NORDEA, PAREX, SEB banks
		String person_ID;
		if (personID != null && personID.indexOf('-') < 0
				&& personID.length() == 11) {
			person_ID = personID.substring(0, 6) + "-" + personID.substring(6);
			log.debug("Changed person ID from " + personID + " to " + person_ID);
		} else
			person_ID = personID;
		log.debug("STS person ID is " + person_ID);
		log.debug("STS attributes are " + attributes);
		log.debug("STS user data are " + userData);
		// Get STSAuthClass ID field from configuration file (for backward
		// compatibility)
		// TODO should change nameid to personid for all XWiki solutions
		String stsAuthClassIdField = props.getStsAuthClassIdField(context);
		String sql = "select distinct doc.fullName from XWikiDocument as doc, BaseObject as obj, StringProperty as nameidprop where"
				+ " doc.fullName=obj.name and obj.className='XWiki.STSAuthClass' and obj.id=nameidprop.id.id and nameidprop.id.name='"
				+ stsAuthClassIdField
				+ "' and nameidprop.value='"
				+ person_ID
				+ "'";
		log.debug("XWiki search SQL string: " + sql);
		List<Object> list = context.getWiki().search(sql, context);
		String validFullUserName = null;
		String validUserName = null;

		if (list.isEmpty()) {
			// User does not exist. Let's generate a unique page name
			log.debug("Did not find XWiki User. Generating it.");
			String userName = generateXWikiUsername(userData, context);
			if ("".equals(userName))
				userName = "User";
			validUserName = context.getWiki().getUniquePageName("XWiki",
					userName, context);
			validFullUserName = "XWiki." + validUserName;
			log.debug("Generated XWiki User Name " + validFullUserName);

		} else {
			validFullUserName = (String) list.get(0);
			log.debug("Found XWiki User " + validFullUserName);

		}

		// we found a user or generated a unique user name
		if (validFullUserName != null) {
			// check if we need to create/update a user page
			String database = context.getDatabase();
			try {
				// Switch to main wiki to force users to be global users
				context.setDatabase(context.getMainXWiki());

				// test if user already exists
				if (!context.getWiki().exists(validFullUserName, context)) {
					log.debug("Need to create user " + validFullUserName);

					// create user
					userData.put("active", "1");

					int result = context.getWiki().createUser(validUserName,
							userData, "XWiki.XWikiUsers",
							"#includeForm(\"XWiki.XWikiUserSheet\")", "edit",
							context);
					if (result < 0) {
						log.error("Failed to create user " + validFullUserName
								+ " with code " + result);
						errorCollector.addError(new Throwable(
								"Failed to create user: "));
						return false;
					}
					XWikiDocument userDoc = context.getWiki().getDocument(
							validFullUserName, context);
					BaseObject userObj = userDoc.getObject("XWiki.XWikiUsers");
					// Fix bug for e-mail field where "$email" value is set on
					// creation
					userObj.set("email", "", context);
					// set user profile to read only with explicit view to not
					// allow
					// changing his
					// name, surname and password
					BaseObject rightsObj = userDoc.getObject(
							"XWiki.XWikiRights", 1);
					rightsObj.set("allow", 1, context);
					rightsObj.set("levels", "view", context);
					// set person ID and authentication type
					BaseObject stsObj = userDoc.newObject("XWiki.STSAuthClass",
							context);
					stsObj.set("nameid", person_ID, context);
					stsObj.set("authtype", authType, context);
					context.getWiki().saveDocument(userDoc, context);
					log.info("New user " + validFullUserName
							+ " has been successfully created. Nameid: "
							+ person_ID + " authtype: " + authType);

				} else {

					XWikiDocument userDoc = context.getWiki().getDocument(
							validFullUserName, context);
					BaseObject userObj = userDoc.getObject("XWiki.XWikiUsers");
					boolean updated = false;
					for (Map.Entry<String, String> entry : userData.entrySet()) {
						String field = entry.getKey();
						String value = entry.getValue();
						BaseProperty prop = (BaseProperty) userObj.get(field);
						String currentValue = (prop == null || prop.getValue() == null) ? null
								: prop.getValue().toString();
						if (value != null && !value.equals(currentValue)) {
							userObj.set(field, value, context);
							updated = true;
						}

					}
					BaseObject stsObj = userDoc.getObject("XWiki.STSAuthClass");
					BaseProperty prop = (BaseProperty) stsObj.get("authtype");
					String currenAuthType = prop.getValue().toString();
					log.debug("currenAuthType: " + currenAuthType);
					if (!authType.equals(currenAuthType)) {
						stsObj.set("authtype", authType, context);
						updated = true;
					}
					if (updated) {
						context.getWiki().saveDocument(userDoc, context);
						log.info("Existing user " + validFullUserName
								+ " has been successfully updated. Nameid: "
								+ person_ID + " authtype: " + authType);
					} else
						log.info("Existing user "
								+ validFullUserName
								+ " was found. Properties were not changed. Nameid: "
								+ person_ID + " authtype: " + authType);
				}
			} catch (Exception e) {
				log.error("Failed to create user " + validFullUserName + "\n"
						+ e);
				errorCollector.addError(new Throwable(
						"Failed to create user: ", e));
				return false;
			} finally {
				context.setDatabase(database);
			}

		}

		log.debug("Setting authentication in session for user "
				+ validFullUserName);
		// mark that we have authenticated the user in the session
		context.getRequest().getSession()
				.setAttribute(props.getAuthField(context), validFullUserName);

		// need to redirect now
		String sourceurl = (String) request.getSession().getAttribute(
				"saml_url");

		log.debug("Redirecting after valid authentication to " + sourceurl);
		try {
			context.getResponse().sendRedirect(sourceurl);
			context.setFinished(true);
			return true;
		} catch (Exception e) {
			log.error("Failed to redirect after authentication\n" + e);
			errorCollector.addError(new Throwable(
					"Failed to redirect after authentication: ", e));
		}
		return false;
	}

    /**
     * checkAuth - Checks authentification session in cookies. If there is data about current user
     * returns it. If there is not an authentification data - then method is trying to login
     * using methadata creating new XWiki Object.
     * 
     * @param context XWikiContext - context of XWiki Engine
     * @throws XWikiUser java.lang.Object extended by java.lang.Throwable </br> extended by java.lang.Exception extended by com.xpn.xwiki.XWikiException
     *
	 * @see com.xpn.xwiki.user.impl.xwiki.AppServerTrustedAuthServiceImpl#checkAuth(com.xpn.xwiki.XWikiContext)
	 */
	@Override
	public XWikiUser checkAuth(XWikiContext context) throws XWikiException {
		log.trace("checkAuth(context)");
		try {
			XWikiRequest request = context.getRequest();
			log.trace("context================\n" + context);
			log.trace("request headers============");
			Enumeration<String> headerNames = request.getHeaderNames();
			while (headerNames.hasMoreElements()) {
				String headerName = headerNames.nextElement();
				log.trace(headerName + "=" + request.getHeader(headerName));
			}
			Enumeration<String> en = request.getParameterNames();
			log.trace("request parameters=============");
			while (en.hasMoreElements()) {
				String paramName = en.nextElement();
				String paramValue = request.getParameter(paramName);
				log.trace(paramName + "=" + URLEncoder.encode(paramValue));
			}
		} catch (Exception e) {
			log.error("Got error during printing request parameters: " + e);
			errorCollector.addError(new Throwable(
					"Got error during printing request parameters: ", e));
		}

		// check in the session if the user is already authenticated
		String stsUserName = (String) context.getRequest().getSession()
				.getAttribute(props.getAuthField(context));
		if (stsUserName == null) {
			// check standard authentication
			if (context.getRequest().getCookie("username") != null
					|| "logout".equals(context.getAction())
					|| context.getAction().startsWith("login")
					|| "1".equals(context.getRequest()
							.getParameter("basicauth"))) {
				log.debug("Fallback to standard authentication");
				return super.checkAuth(context);
			}
			// check if we have a STS Response to verify
			// (this sets getAuthField value for the next pass)
			if (checkSTSResponse(context))
				return null;
		} else {
			log.debug("Found authentication of user " + stsUserName);
			log.info(errorCollector.listErrors());
			if (context.isMainWiki()) {
				return new XWikiUser(stsUserName);
			} else {
				return new XWikiUser(context.getMainXWiki() + ":" + stsUserName);
			}
		}
		return null;
	}

	/**
	 * {@inheritDoc}
	 * 
	 * @see com.xpn.xwiki.user.impl.xwiki.AppServerTrustedAuthServiceImpl#checkAuth(java.lang.String,
	 *      java.lang.String, java.lang.String, com.xpn.xwiki.XWikiContext)
	 */
	@Override
	public XWikiUser checkAuth(String username, String password,
			String rememberme, XWikiContext context) throws XWikiException {
		log.trace("checkAuth(): " + username + ", " + password + ", "
				+ rememberme);
		String auth = getAuthFieldValue(context);
		if ((auth == null) || "".equals(auth)) {
			return super.checkAuth(context);
		} else {
			return checkAuth(context);
		}
	}

    /**
     * getter to get authField (value) from XWikiContext
     * 
     * @param context XWikiContext - context of XWiki Engine
     * @throws XWikiUser java.lang.Object extended by java.lang.Throwable </br> extended by java.lang.Exception extended by com.xpn.xwiki.XWikiException
     *
	 * @see com.xpn.xwiki.user.impl.xwiki.AppServerTrustedAuthServiceImpl#checkAuth(com.xpn.xwiki.XWikiContext)
	 */
	private String getAuthFieldValue(XWikiContext context) {
		String val = (String) context.getRequest().getSession(true)
				.getAttribute(props.getAuthField(context));
		log.trace("getAuthFieldValue(): " + val);
		return val;
	}
	
    /**
     * getExtendedInformation 
     * Get Extended Information from context according to data parameter
     * 
     * @param data Map - data acccording which - will be extracted extended information
     * @param context XWikiContext - context to get data from
     * @return mapped information in format Map<String, String> 
	 * @see com.xpn.xwiki.user.impl.xwiki.AppServerTrustedAuthServiceImpl#checkAuth(com.xpn.xwiki.XWikiContext)
	 */
	private Map<String, String> getExtendedInformation(Map data,
			XWikiContext context) {
		log.trace("ExtendedInformation()");
		Map<String, String> extInfos = new HashMap<String, String>();
		for (Map.Entry<String, String> entry : getFieldMapping(context)
				.entrySet()) {
			String dataValue = (String) data.get(entry.getKey());
			log.trace(" STS:" + entry.getKey() + ", value:" + dataValue
					+ ", xwiki field:" + entry.getValue());
			if (dataValue != null) {
				extInfos.put(entry.getValue(), dataValue);
			}
		}
		return extInfos;
	}

	/**
	 * @param context
	 *            the XWiki context.
	 * @return the fields to use to generate the xwiki user name
	 */
	private String[] getXWikiUsernameRule(XWikiContext context) {
		String userFields = props.getUsernameRule(context);
		log.trace("XWikiUsernameRule(): " + userFields);
		return userFields.split(",");
	}

    /**
     * generateXWikiUsername(Map userData, XWikiContext context)
     * generate username according to XWikiContext and userData fields
     * @param userData Map - data acccording which - will be extracted extended information
     * @param context XWikiContext - context to get data from
     * @return userName String
	 * @see com.xpn.xwiki.user.impl.xwiki.AppServerTrustedAuthServiceImpl#checkAuth(com.xpn.xwiki.XWikiContext)
	 */
	private String generateXWikiUsername(Map userData, XWikiContext context) {
		log.trace("generateXWikiUsername()");
		String[] userFields = getXWikiUsernameRule(context);
		StringBuilder userName = new StringBuilder("");
		for (String field : userFields) {
			String value = (String) userData.get(field);
			if (value != null && value.length() > 0) {
				userName.append(value);
			}
		}
		log.debug("XWikiUsername: " + userName);
		return userName.toString();
	}

	/**
	 * @param context
	 *            the XWiki context.
	 * @return the mapping between HTTP header fields names and XWiki user
	 *         profile fields names.
	 */
	private static Map<String, String> getFieldMapping(XWikiContext context) {
		log.trace("getFieldMapping()");
		if (userMappings == null) {
			userMappings = new HashMap<String, String>();

			String fieldMapping = props.getFieldMapping(context);
			String[] fields = fieldMapping.split(",");

			for (int j = 0; j < fields.length; j++) {
				String[] field = fields[j].split("=");
				if (2 == field.length) {
					String xwikiattr = field[0].trim();
					String headerattr = field[1].trim();
					userMappings.put(headerattr, xwikiattr);
				} else {
					log.error("Error parsing STS fields_mapping attribute in xwiki.cfg: "
							+ fields[j]);
				}
			}
		}
		return userMappings;
	}

	/**
	 * Put listed errors into log
	 */
	public void listErrors() {
		log.info(errorCollector.listErrors());
		errorCollector.clearErrorList();
	}
}
