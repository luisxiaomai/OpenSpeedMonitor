/* 
* OpenSpeedMonitor (OSM)
* Copyright 2014 iteratec GmbH
* 
* Licensed under the Apache License, Version 2.0 (the "License"); 
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
* 
* 	http://www.apache.org/licenses/LICENSE-2.0
* 
* Unless required by applicable law or agreed to in writing, software 
* distributed under the License is distributed on an "AS IS" BASIS, 
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
* See the License for the specific language governing permissions and 
* limitations under the License.
*/

package de.iteratec.osm.api

import grails.converters.JSON
import grails.validation.Validateable

import javax.persistence.NoResultException

import org.codehaus.groovy.grails.web.mapping.LinkGenerator
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.format.DateTimeFormatter
import org.joda.time.format.ISODateTimeFormat
import org.springframework.http.HttpStatus

import de.iteratec.osm.measurement.schedule.JobGroup
import de.iteratec.osm.measurement.schedule.dao.JobGroupDaoService
import de.iteratec.osm.csi.Page
import de.iteratec.osm.measurement.schedule.dao.PageDaoService
import de.iteratec.osm.api.json.JSONLocationBox
import de.iteratec.osm.api.json.JSONNameBox
import de.iteratec.osm.api.json.Result
import de.iteratec.osm.csi.TimeToCsMappingService
import de.iteratec.osm.csi.weighting.WeightFactor
import de.iteratec.osm.result.EventResult
import de.iteratec.osm.result.JobResult
import de.iteratec.osm.result.JobResultService
import de.iteratec.osm.result.MeasuredEvent
import de.iteratec.osm.result.MeasuredValueTagService
import de.iteratec.osm.result.MvQueryParams
import de.iteratec.osm.result.dao.MeasuredEventDaoService
import de.iteratec.osm.util.PerformanceLoggingService
import de.iteratec.osm.util.PerformanceLoggingService.IndentationDepth
import de.iteratec.osm.util.PerformanceLoggingService.LogLevel
import de.iteratec.osm.measurement.environment.Browser
import de.iteratec.osm.measurement.environment.dao.BrowserDaoService
import de.iteratec.osm.measurement.environment.Location
import de.iteratec.osm.measurement.environment.dao.LocationDaoService

/**
 * RestApiController
 * A controller class handles incoming web requests and performs actions such as redirects, rendering views and so on.
 */
class RestApiController {

	public static final DateTimeFormatter API_DATE_FORMAT = ISODateTimeFormat.basicDateTimeNoMillis()
	
	JobGroupDaoService jobGroupDaoService;
	PageDaoService pageDaoService; 
	MeasuredEventDaoService measuredEventDaoService; 
	BrowserDaoService browserDaoService; 
	LocationDaoService locationDaoService;
	JobResultService jobResultService;
	ShopCsiService shopCsiService
	MeasuredValueTagService measuredValueTagService
	PerformanceLoggingService performanceLoggingService
	TimeToCsMappingService timeToCsMappingService
	
	/**
	 * <p>
	 * A request to receive results via REST-API.
	 * </p>
	 * 
	 * @author mze
	 * @since IT-81
	 */
	@Validateable
	public static class ResultsRequestCommand {

		/**
		 * <p>
		 * The start of the time-range for that results should be delivered; 
		 * this time-stamp is to be treated as inclusive. The format must 
		 * satisfy the format specified in ISO8601.
		 * </p>
		 * 
		 * <p>
		 * Not <code>null</code>; not {@linkplain String#isEmpty() empty}.
		 * </p>
		 * 
		 * @see ISODateTimeFormat
		 */
		String timestampFrom;

		/**
		 * <p>
		 * The end of the time-range for that results should be delivered;
		 * this time-stamp is to be treated as inclusive. The format must
		 * satisfy the format specified in ISO8601.
		 * </p>
		 * 
		 * <p>
		 * Not <code>null</code>; not {@linkplain String#isEmpty() empty}.
		 * </p>
		 *
		 * @see ISODateTimeFormat
		 */
		String timestampTo;

		/**
		 * <p>
		 * The name of the system (CSI Group/Folder/Shop) for that results 
		 * should be delivered.
		 * </p>
		 * 
		 * <p>
		 * Not <code>null</code>; not {@linkplain String#isEmpty() empty}.
		 * </p>
		 * 
		 * @see JobGroup
		 * @see JobGroup#getName()
		 */
		String system;

		/**
		 * <p>
		 * The page for that results should be delivered. If <code>null</code>
		 * or {@linkplain String#isEmpty() empty} results for all pages will be
		 * delivered.
		 * </p>
		 * 
		 * @see Page
		 * @see Page#getName()
		 */
		String page;

		/**
		 * <p>
		 * The step (measured event) for that results should be delivered. 
		 * If <code>null</code> or {@linkplain String#isEmpty() empty} 
		 * results for all steps will be delivered.
		 * </p>
		 * 
		 * @see MeasuredEvent
		 * @see MeasuredEvent#getName()
		 */
		String step;

		/**
		 * <p>
		 * The browser for that results should be delivered.
		 * If <code>null</code> or {@linkplain String#isEmpty() empty}
		 * results for all browser will be delivered.
		 * </p>
		 *
		 * @see Browser
		 * @see Browser#getName()
		 */
		String browser;

		/**
		 * <p>
		 * The location for that results should be delivered.
		 * If <code>null</code> or {@linkplain String#isEmpty() empty}
		 * results for all locations will be delivered.
		 * </p>
		 *
		 * @see Location
		 * @see Location#getLocation()
		 */
		String location;
		
		/**
		 * Whether or not to pretty-print the json-response.
		 */
		Boolean pretty


		/**
		 * Constraints needs to fit.
		 */
		static constraints = {
			timestampFrom(nullable:false, blank:false)
			timestampTo(nullable:false, blank:false)
			system(nullable:false, blank:false)
			page(nullable:true, blank:true)
			step(nullable:true, blank:true)
			browser(nullable:true, blank:true)
			location(nullable:true, blank:true)
			pretty(nullable:true, blank:true)
		}
		
		/**
		 * <p>
		 * Creates {@link MvQueryParams} based on this command. This command
		 * need to be valid for this operation to be successful.
		 * </p>
		 *
		 * @return not <code>null</code>.
		 * @throws IllegalStateException
		 *         if called on an invalid instance.
		 * @throws NoResultException 
		 *         if at least one of the specified parameters (system, page, 
		 *         event, location) could not be found.
		 */
		public MvQueryParams createMvQueryParams(
					JobGroupDaoService jobGroupDaoService, 
					PageDaoService pageDaoService, 
					MeasuredEventDaoService measuredEventDaoService,
					BrowserDaoService browserDaoService,
					LocationDaoService locationDaoService) throws IllegalStateException, NoResultException
		{
			if( !this.validate() )
			{
				throw new IllegalStateException('Query params are not available from an invalid command.')
			}

			MvQueryParams result = new MvQueryParams();
			
			// system
			Set<JobGroup> allCSISystems = jobGroupDaoService.findCSIGroups();
			JobGroup theSystem = allCSISystems.find({it.getName() == system});
			if( theSystem == null )
			{
				throw new NoResultException("Can not find CSI system named: " + system);
			}
			result.jobGroupIds.add(theSystem.getId());

			// page
			if( page )
			{
				Set<Page> allPages = pageDaoService.findAll();
				Page thePage = allPages.find({it.getName() == page});
				if( thePage == null )
				{
					throw new NoResultException("Can not find Page named: " + page);
				}
				result.pageIds.add(thePage.getId());
			}
			
			if( step )
			{
				MeasuredEvent theStep = measuredEventDaoService.tryToFindByName(step);
				if( theStep == null )
				{
					throw new NoResultException("Can not find step named: " + step);
				}
				result.measuredEventIds.add(theStep.getId());
			} 
			
			if( browser )
			{
				Browser theBrowser = browserDaoService.tryToFindByNameOrAlias(browser);
				if( theBrowser == null )
				{
					throw new NoResultException("Can not find browser named: " + browser);
				}
				result.browserIds.add(theBrowser.getId());
			}
			
			if( location )
			{
				Location theLocation = locationDaoService.tryToFindByWPTLocation(location);
				if( theLocation == null )
				{
					throw new NoResultException("Can not find location which queue named: " + location);
				}
				result.locationIds.add(theLocation.getId());
			}
			
			return result;
		}
	}
	
	LinkGenerator grailsLinkGenerator;
	
	/**
	 * <p>
	 * Performs a redirect with HTTP status code 303 (see other).
	 * </p>
	 *
	 * <p>
	 * COPIED FROM CSI DASHBOARD - SHOULD BE EXTRACTED TO A UTIL!
	 * </p>
	 *
	 * <p>
	 * Using this redirect enforces the client to perform the next request
	 * with the HTTP method GET.
	 * This method SHOULD be used in a redirect-after-post situation.
	 * </p>
	 *
	 * <p>
	 * After using this method, the response should be considered to be
	 * committed and should not be written to.
	 * </p>
	 *
	 * @param actionNameToRedirectTo The Name of the action to redirect to;
	 *        not <code>null</code>.
	 *
	 * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3.4"
	 *      >http://tools.ietf.org/html/rfc2616#section-10.3.4</a>
	 */
	private void redirectWith303(String actionNameToRedirectTo)
	{
		// There is a missing feature to do this:
		// http://jira.grails.org/browse/GRAILS-8829

		// Workaround based on:
		// http://fillinginthegaps.wordpress.com/2008/12/26/grails-301-moved-permanently-redirect/
		String uri = grailsLinkGenerator.link(action: actionNameToRedirectTo)
		response.setStatus(303)
		response.setHeader("Location", uri)
		render(status:303)
	}
	
	/**
	 * Redirects to {@link #getResultsDocumentation()}.
	 *
	 * @return Nothing, redirects immediately.
	 */
	Map<String, Object> index() {
		redirectWith303('getResultsDocumentation')
	}
	
	/**
	 * <p>
	 * Renders an API documentation HTML for 
	 * {@link #getResults(ResultsRequestCommand)}.
	 * </p>
	 * 
	 * @since IT-81
	 */
	public Map<String, Object> man() {
		return [:]; // Just render the documentation gsp. 
	}
	
	/**
	 * <p>
	 * Returns a set of all Systems (CSI groups) as JSON.
	 * </p>
	 * 
	 * @return Nothing, a JSON is sent before.
	 * @see JobGroup
	 */
	public Map<String, Object> allSystems() {
		Set<JobGroup> systems = jobGroupDaoService.findCSIGroups();
		Collection<JSONNameBox> result = systems.collect({ 
				new JSONNameBox(it.name) });
		
		return sendObjectAsJSON(result, params.pretty && params.pretty == 'true');
	}
	
	/**
	 * <p>
	 * Returns a set of all steps (measured events) as JSON.
	 * </p>
	 *
	 * @return Nothing, a JSON is sent before.
	 * @see MeasuredEvent
	 */
	public Map<String, Object> allSteps() {
		Set<MeasuredEvent> events = measuredEventDaoService.findAll();
		Collection<JSONNameBox> result = events.collect({
			new JSONNameBox(it.name) });
		
		return sendObjectAsJSON(result, params.pretty && params.pretty == 'true');
	}
	
	/**
	 * <p>
	 * Returns a set of all browser as JSON.
	 * </p>
	 *
	 * @return Nothing, a JSON is sent before.
	 * @see Browser
	 */
	public Map<String, Object> allBrowsers() {
		Set<Browser> browser = browserDaoService.findAll();
		Collection<JSONNameBox> result = browser.collect({
			new JSONNameBox(it.name) });
		
		return sendObjectAsJSON(result, params.pretty && params.pretty == 'true');
	}
	
	/**
	 * <p>
	 * Returns a set of all pages as JSON.
	 * </p>
	 *
	 * @return Nothing, a JSON is sent before.
	 * @see Page
	 */
	public Map<String, Object> allPages() {
		Set<Page> pages = pageDaoService.findAll();
		Collection<JSONNameBox> result = pages.collect({
			new JSONNameBox(it.name) });
		
		return sendObjectAsJSON(result, params.pretty && params.pretty == 'true');
	}
	
	/**
	 * <p>
	 * Returns a set of all locations as JSON.
	 * </p>
	 *
	 * @return Nothing, a JSON is sent before.
	 * @see Page
	 */
	public Map<String, Object> allLocations() {
		Set<Location> locations = locationDaoService.findAll();
		Collection<JSONNameBox> result = locations.collect({
			new JSONLocationBox(it.location) });
		
		return sendObjectAsJSON(result, params.pretty && params.pretty == 'true');
	}
	
	/**
	 * The maximum duration of time-frame sent to {@link 
	 * #getResults(ResultsRequestCommand)} in hours.
	 */
	private static int MAX_TIME_FRAME_DURATION_IN_HOURS = 48;
	
	/**
	 * The maximum duration of time-frame sent to {@link
	 * #getSystemCsi(ResultsRequestCommand)} in days.
	 */
	private static int MAX_TIME_FRAME_DURATION_IN_DAYS_CSI = 8;
	
	/**
	 * <p>
	 * Returns results for specified request as JSON 
	 * (see {@link ResultsRequestCommand}).
	 * </p>
	 * 
	 * <p>
	 * Potential outcomes of this method:
	 * <dl>
	 * 	<dt>HTTP status 200 OK</dt>
	 *  <dd>The request handled successful, a 
	 * 	result in JSON notation is returned. TOOD Give response example
	 * 
	 *  The response is of type application/json as described in RFC4627.
	 *  </dd>
	 *  <dt>HTTP status 400 Bad Request</dt>
	 *  <dd>The end of the requested time frame is before the start of it. 
	 *  For sure, this is invalid. The end of the time-frame need 
	 *  to be after its start. A text/plain error message 
	 *  with details is attached as response.
	 *  </dd>
	 *  <dt>HTTP status 413 Request Entity Too Large</dt>
	 *  <dd>
	 *  The requested time-frames duration in days is wider than 
	 *  {@link #MAX_TIME_FRAME_DURATION_IN_DAYS}. A text/plain error message 
	 *  with details is attached as response.
	 *  </dd>
	 *  <dt>HTTP status 404 Not Found</dt>
	 *  <dd>
	 *  If at least one of the requested elements was not found. If no further 
	 *  parameters specified, this need to be the specified system otherwise it
	 *  could be any of them.
	 *  A text/plain error message with details is attached as response.
	 *  </dd>
	 * </dl>
	 * </p>
	 * 
	 * @param cmd 
	 *         The command which need to be valid for a successful 
	 *         processing, not <code>null</code>.
	 * @return Nothing, a JSON or an error status code is sent before.
	 * 
	 * @see <a href="http://tools.ietf.org/html/rfc4627">RFC4627</a>
	 */
	public Map<String, Object> getResults(ResultsRequestCommand cmd) {
		
		//FIXME: REMOVE IF Databinder is changed to new version
		fixCommand(cmd);
		
		DateTime startDateTimeInclusive = API_DATE_FORMAT.parseDateTime(cmd.timestampFrom);
		DateTime endDateTimeInclusive = API_DATE_FORMAT.parseDateTime(cmd.timestampTo); 
		
		if( endDateTimeInclusive.isBefore(startDateTimeInclusive) ) {
			sendSimpleResponseAsStream(response, HttpStatus.BAD_REQUEST, 'The end of requested time-frame could not be before start of time-frame.')
			return null
		}
		
		Duration requestedDuration = new Duration(startDateTimeInclusive, endDateTimeInclusive);
		
		if( requestedDuration.getStandardHours() > MAX_TIME_FRAME_DURATION_IN_HOURS ) {
			String errorMessage = 'The requested time-frame is wider than ' + 
				MAX_TIME_FRAME_DURATION_IN_HOURS + 
				' hours. This is too large to process. Please choose a smaler time-frame.'
			sendSimpleResponseAsStream(response, HttpStatus.REQUEST_ENTITY_TOO_LARGE, errorMessage)
			return null
		}
		
		Date startTimeInclusive = startDateTimeInclusive.toDate();
		Date endTimeInclusive = endDateTimeInclusive.toDate();

		MvQueryParams queryParams = null;
		try {
			queryParams = cmd.createMvQueryParams(jobGroupDaoService, pageDaoService, measuredEventDaoService, browserDaoService, locationDaoService);
		} catch(NoResultException nre)
		{
			sendSimpleResponseAsStream(response, HttpStatus.NOT_FOUND, 'Some request arguments could not be found: ' + nre.getMessage())
			return null
		}
		
		response.setContentType("application/json;charset=UTF-8");
		response.status=200;
		
		List<Result> results = new LinkedList<Result>();
		
		Collection<JobResult> matchingJobResults
		performanceLoggingService.logExecutionTime(LogLevel.INFO, 'getting job-results', IndentationDepth.ONE) {
			matchingJobResults = jobResultService.findJobResultsByQueryParams(queryParams, startTimeInclusive, endTimeInclusive);
		}
		
		performanceLoggingService.logExecutionTime(LogLevel.INFO, 'assembling results for json', IndentationDepth.ONE) {
			for(JobResult eachJobResult : matchingJobResults)
			{
				List<EventResult> eventResultsofCurrentJobResultMatchingGivenQueryParams = eachJobResult.getEventResults().findAll{ 
					it.tag ==~ measuredValueTagService.getTagPatternForResultMeasuredValues(queryParams)
				}
				for(EventResult eachEventResult : eventResultsofCurrentJobResultMatchingGivenQueryParams){
					if( eachEventResult.customerSatisfactionInPercent ){
						results.add(new Result(eachJobResult, eachEventResult));
					}	
				}
			}
		}
		return sendObjectAsJSON(results, params.pretty && params.pretty == 'true');
	}

	private void sendSimpleResponseAsStream(javax.servlet.http.HttpServletResponse response, HttpStatus httpStatus, String message) {
		response.setContentType('text/plain;charset=UTF-8')
		response.status=httpStatus.value()

		Writer textOut = new OutputStreamWriter(response.getOutputStream())
		textOut.write(message)
		response.status=httpStatus

		textOut.flush()
		response.getOutputStream().flush()

		textOut.flush()
		response.getOutputStream().flush()
	}
	
	public Map<String, Object> getSystemCsi(ResultsRequestCommand cmd) {
		
		//FIXME: REMOVE IF Databinder is changed to new version
		fixCommand(cmd);
		
		DateTime startDateTimeInclusive = API_DATE_FORMAT.parseDateTime(cmd.timestampFrom);
		DateTime endDateTimeInclusive = API_DATE_FORMAT.parseDateTime(cmd.timestampTo);
		
		if (log.infoEnabled) {}
		
		if( endDateTimeInclusive.isBefore(startDateTimeInclusive) ) {
			response.setContentType('text/plain;charset=UTF-8');
			response.status=400; // BAD REQUEST
			
			Writer textOut = new OutputStreamWriter(response.getOutputStream());
			textOut.write('The end of requested time-frame could not be before start of time-frame.');
			
			textOut.flush();
			response.getOutputStream().flush();
			return null;
		}
		
		Duration requestedDuration = new Duration(startDateTimeInclusive, endDateTimeInclusive);
		
		if( requestedDuration.getStandardDays() > MAX_TIME_FRAME_DURATION_IN_DAYS_CSI ) {
			response.setContentType('text/plain;charset=UTF-8');
			response.status=413; // Request Entity Too Large
			
			Writer textOut = new OutputStreamWriter(response.getOutputStream());
			textOut.write('The requested time-frame is wider than ' +
				MAX_TIME_FRAME_DURATION_IN_DAYS_CSI +
				' days. This is too large to process. Please choose a smaler time-frame.');
			
			textOut.flush();
			response.getOutputStream().flush();
			return null;
		}
		
		Date startTimeInclusive = startDateTimeInclusive.toDate();
		Date endTimeInclusive = endDateTimeInclusive.toDate();

		MvQueryParams queryParams = null;
		try {
			queryParams = cmd.createMvQueryParams(jobGroupDaoService, pageDaoService, measuredEventDaoService, browserDaoService, locationDaoService);
		} catch(NoResultException nre)
		{
			response.setContentType('text/plain;charset=UTF-8');
			response.status=404; // NOT FOUND (send Error does not work probably with Grails, it would render the default error page. So we use the deprecated setter.)
			
			Writer textOut = new OutputStreamWriter(response.getOutputStream());
			textOut.write('Some request arguements could not be found: ' + nre.getMessage());
			
			textOut.flush();
			response.getOutputStream().flush();
			return null;
		}
		
		SystemCSI systemCsiToReturn
		try {
			systemCsiToReturn = shopCsiService.retrieveSystemCsiByRawData(startDateTimeInclusive, endDateTimeInclusive, queryParams, [WeightFactor.PAGE, WeightFactor.BROWSER] as Set)
		} catch (IllegalArgumentException e) {
			response.setContentType('text/plain;charset=UTF-8');
			response.status=404; // NOT FOUND (send Error does not work probably with Grails, it would render the default error page. So we use the deprecated setter.)
			
			Writer textOut = new OutputStreamWriter(response.getOutputStream());
			textOut.write(e.getMessage())
			
			textOut.flush();
			response.getOutputStream().flush();
			return null;
		}
		
		return sendObjectAsJSON(systemCsiToReturn, params.pretty && params.pretty == 'true');
		
	}
	
	/**
	 * Calculates docCompleteTimeInMillisecs to customer satisfaction. Assumption is that doc complete time is for {@link Page} with name pageName.
	 * For the calculation most recent csi-mapping is used (see {@link CustomerFrustration}).
	 * @param docCompleteTimeInMillisecs
	 * 	Doc complete time to translate.
	 * @param pageName
	 * 	Name of the page the doc complete time was measured for.
	 * @return
	 * 	The calculated customer satisfaction for the given doc complete time.
	 */
	public Map<String, Object> translateToCustomerSatisfaction(Integer docCompleteTimeInMillisecs, String pageName){
		if( docCompleteTimeInMillisecs == null || pageName == null ) {
			sendSimpleResponseAsStream(response, HttpStatus.BAD_REQUEST, 'Params docCompleteTimeInMillisecs AND pageName must be set.')
			return null
		}
		Page page = Page.findByName(pageName) 
		if( page == null ) {
			sendSimpleResponseAsStream(response, HttpStatus.BAD_REQUEST, "Page with name ${pageName} couldn't be found")
			return null
		}
		return sendObjectAsJSON(
			['docCompleteTimeInMillisecs': docCompleteTimeInMillisecs, 'customerSatisfactionInPercent': timeToCsMappingService.getCustomerSatisfactionInPercent(docCompleteTimeInMillisecs, page)], 
			params.pretty && params.pretty == 'true'
		)
	}
	
	/**
	 * Returns the complete list of {@link CustomerFrustration}s for {@link Page} with given pageName.
	 * @param pageName
	 * 	Name of the page the {@link CustomerFrustration}s should be delivered.
	 * @return The complete list of {@link CustomerFrustration}s for {@link Page} with given pageName.
	 */
	public Map<String, Object> getCsiFrustrationTimings(String pageName){
		if( pageName == null ) {
			sendSimpleResponseAsStream(response, HttpStatus.BAD_REQUEST, 'Param pageName must be set.')
			return null
		}
		Page page = Page.findByName(pageName)
		if( page == null ) {
			sendSimpleResponseAsStream(response, HttpStatus.BAD_REQUEST, "Page with name ${pageName} couldn't be found.")
			return null
		}
		List<Integer> cachedFrustrations = timeToCsMappingService.getCachedFrustrations(page)
		return sendObjectAsJSON(
			['page': pageName, 'cachedFrustrations': cachedFrustrations, 'count': cachedFrustrations.size()],
			params.pretty && params.pretty == 'true'
		)
	}
	
	/**
	 * <p>
	 * Sends the object rendered as JSON. All public getters are used to 
	 * render the result. This call should be placed as last statement, the
	 * return statement, of an action.
	 * </p>
	 * 
	 * @param objectToSend 
	 *         The object to render end to be sent to the client, 
	 *         not <code>null</code>.
	 * @param usePrettyPrintingFormat
	 *         Set to <code>true</code> if the JSON should be "pretty
	 *         formated" (easy to read but larger file).
	 * @return Always <code>null</code>.
	 * @throws NullPointerException 
	 *         if {@code objectToSend} is <code>null</code>.
	 */
	private Object sendObjectAsJSON(Object objectToSend, boolean usePrettyPrintingFormat)
	{
		JSON converter = new JSON(target: objectToSend)
		converter.setPrettyPrint(usePrettyPrintingFormat)
		render converter
		return null
	}
	
	
	/**
	 * Fix to support Databinding of URLMapping Variables to CommandObjects, 
	 * can be removed if the new Databinder is used.
	 * 
	 * @author rhe
	 * @since IT-188
	 * @see IT-230
	 */
	private void fixCommand(ResultsRequestCommand cmd) {
		
		cmd.timestampFrom=params.timestampFrom;
		cmd.timestampTo=params.timestampTo;
		cmd.system=params.system;
		
	}

}
