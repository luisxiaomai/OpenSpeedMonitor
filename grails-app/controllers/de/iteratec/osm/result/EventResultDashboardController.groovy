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

package de.iteratec.osm.result

import de.iteratec.osm.p13n.CookieBasedSettingsService
import grails.validation.Validateable

import java.text.NumberFormat
import java.util.regex.Pattern

import org.codehaus.groovy.grails.web.mapping.LinkGenerator
import org.joda.time.DateTime
import org.joda.time.Duration
import org.joda.time.Interval
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import org.springframework.beans.propertyeditors.CustomDateEditor
import org.springframework.web.servlet.support.RequestContextUtils
import org.supercsv.encoder.DefaultCsvEncoder
import org.supercsv.io.CsvListWriter
import org.supercsv.prefs.CsvPreference
import de.iteratec.osm.report.chart.dao.AggregatorTypeDaoService
import de.iteratec.osm.report.chart.OsmChartGraph
import de.iteratec.osm.report.chart.OsmChartPoint
import de.iteratec.osm.report.chart.MeasuredValueUtilService
import de.iteratec.osm.measurement.schedule.JobGroup
import de.iteratec.osm.measurement.schedule.dao.JobGroupDaoService
import de.iteratec.osm.csi.Page
import de.iteratec.osm.measurement.schedule.dao.PageDaoService
import de.iteratec.osm.report.chart.AggregatorType
import de.iteratec.osm.report.chart.MeasurandGroup
import de.iteratec.osm.report.chart.MeasuredValueInterval
import de.iteratec.osm.util.TreeMapOfTreeMaps
import de.iteratec.osm.util.ControllerUtils
import de.iteratec.osm.util.CustomDateEditorRegistrar
import de.iteratec.osm.measurement.environment.Browser
import de.iteratec.osm.measurement.environment.dao.BrowserDaoService
import de.iteratec.osm.measurement.environment.Location
import de.iteratec.osm.measurement.environment.dao.LocationDaoService
import de.iteratec.osm.util.I18nService
import de.iteratec.osm.report.chart.OsmChartAxis;

class EventResultDashboardController {

	static final int RESULT_DASHBOARD_MAX_POINTS_PER_SERIES = 100000

	AggregatorTypeDaoService aggregatorTypeDaoService
	JobGroupDaoService jobGroupDaoService
	PageDaoService pageDaoService
	BrowserDaoService browserDaoService
	LocationDaoService locationDaoService
	EventResultService eventResultService
	MeasuredValueUtilService measuredValueUtilService
	EventResultDashboardService eventResultDashboardService
	PageService pageService
	I18nService i18nService
	CookieBasedSettingsService cookieBasedSettingsService

	/**
	 * The Grails engine to generate links.
	 *
	 * @see http://mrhaki.blogspot.ca/2012/01/grails-goodness-generate-links-outside.html
	 */
	LinkGenerator grailsLinkGenerator

	public final static Integer LINE_CHART_SELECTION = 0;
	public final static Integer POINT_CHART_SELECTION = 1;

	public final static Integer EXPECTED_RESULTS_PER_DAY = 50;
	public final
	static Map<CachedView, Map<String, List<String>>> AGGREGATOR_GROUP_VALUES = ResultMeasuredValueService.getAggregatorMapForOptGroupSelect()

	public final static List<String> AGGREGATOR_GROUP_LABELS = [
			'de.iteratec.isocsi.csi.per.job',
			'de.iteratec.isocsi.csi.per.page',
			'de.iteratec.isocsi.csi.per.csi.group'
	]

	List<Long> measuredValueIntervals = [
			MeasuredValueInterval.RAW,
			MeasuredValueInterval.HOURLY,
			MeasuredValueInterval.DAILY,
			MeasuredValueInterval.WEEKLY
	]


	public final static String DATE_FORMAT_STRING = 'dd.mm.yyyy';
	public final static int MONDAY_WEEKSTART = 1

	//def timeFrames = [0, 900, 1800, 3600, 10800, 21600, 43200, 86400, 604800, 1209600, 2419200]
	//	def intervals = ['auto', 'max', '5min', '15min', '30min', '1h', '3h', '6h', '12h', 'daily', 'weekly']

	def intervals = [
			'not',
			'hourly',
			'daily',
			'weekly'
	]

	def index() {
		redirect(action: 'showAll')
	}

	/**
	 * Thats the view used to show results with previous selection of date
	 * range, groups and more filter criteria.
	 *
	 * @param cmd The request / command send to this action,
	 *            not <code>null</code>.
	 * @return A model map with event result data to be used by the 
	 *         corresponding GSP, not <code>null</code> and never
	 * {@linkplain Map#isEmpty() empty}.
	 */
	Map<String, Object> showAll(ShowAllCommand cmd) {

		Map<String, Object> modelToRender = constructStaticViewDataOfShowAll();
		cmd.copyRequestDataToViewModelMap(modelToRender);

		if (!ControllerUtils.isEmptyRequest(params)) {
			if (!cmd.validate()) {
				modelToRender.put('command', cmd)
			} else {
				// For validation errors if there is a request and it is not valid:

				boolean warnAboutLongProcessingTimeInsteadOfShowingData = false;
				if (!cmd.overwriteWarningAboutLongProcessingTime) {

					int countOfSelectedBrowser = cmd.selectedBrowsers.size();
					if (countOfSelectedBrowser < 1) {
						countOfSelectedBrowser = ((List) modelToRender.get('browsers')).size();
					}

					int countOfSelectedAggregators = cmd.selectedAggrGroupValuesCached.size() + cmd.selectedAggrGroupValuesUnCached.size();

					warnAboutLongProcessingTimeInsteadOfShowingData = shouldWarnAboutLongProcessingTime(cmd.getSelectedTimeFrame(), cmd.getSelectedInterval(), countOfSelectedAggregators, cmd.selectedFolder.size(), countOfSelectedBrowser, cmd.selectedPage.size());

				}

				if (warnAboutLongProcessingTimeInsteadOfShowingData) {
					modelToRender.put('warnAboutLongProcessingTime', true)
				} else {
					fillWithMeasuredValueData(modelToRender, cmd);
				}
			}
		}

		log.info("from=${modelToRender['from']}")
		log.info("to=${modelToRender['to']}")
		log.info("fromHour=${modelToRender['fromHour']}")
		log.info("toHour=${modelToRender['toHour']}")
		return modelToRender
	}


	private void fillWithMeasuredValueData(Map<String, Object> modelToRender, ShowAllCommand cmd) {
		Interval timeFrame = cmd.getSelectedTimeFrame();

		List<String> aggregatorNames = [];
		aggregatorNames.addAll(cmd.getSelectedAggrGroupValuesCached());
		aggregatorNames.addAll(cmd.getSelectedAggrGroupValuesUnCached());

		List<AggregatorType> aggregators = getAggregators(aggregatorNames);

		LinkedList<OsmChartAxis> labelToDataMap = new LinkedList<OsmChartAxis>();
		labelToDataMap.add(new OsmChartAxis(i18nService.msg("de.iteratec.isr.measurand.group.UNDEFINED",
				MeasurandGroup.UNDEFINED.toString()), MeasurandGroup.UNDEFINED, "", 1, OsmChartAxis.RIGHT_CHART_SIDE));
		labelToDataMap.add(new OsmChartAxis(i18nService.msg("de.iteratec.isr.measurand.group.REQUEST_COUNTS",
				MeasurandGroup.REQUEST_COUNTS.toString()), MeasurandGroup.REQUEST_COUNTS, "c", 1, OsmChartAxis.RIGHT_CHART_SIDE));
		labelToDataMap.add(new OsmChartAxis(i18nService.msg("de.iteratec.isr.measurand.group.LOAD_TIMES",
				MeasurandGroup.LOAD_TIMES.toString()), MeasurandGroup.LOAD_TIMES, "s", 1000, OsmChartAxis.LEFT_CHART_SIDE));
		labelToDataMap.add(new OsmChartAxis(i18nService.msg("de.iteratec.isr.measurand.group.REQUEST_SIZES",
				MeasurandGroup.REQUEST_SIZES.toString()), MeasurandGroup.REQUEST_SIZES, "KB", 1000, OsmChartAxis.RIGHT_CHART_SIDE));
		labelToDataMap.add(new OsmChartAxis(i18nService.msg("de.iteratec.isr.measurand.group.PERCENTAGES",
				MeasurandGroup.PERCENTAGES.toString()), MeasurandGroup.PERCENTAGES, "%", 0.01, OsmChartAxis.RIGHT_CHART_SIDE));

		MvQueryParams queryParams = cmd.createMvQueryParams();

		List<OsmChartGraph> graphCollection = eventResultDashboardService.getEventResultDashboardHighchartGraphs(
				timeFrame.getStart().toDate(), timeFrame.getEnd().toDate(), cmd.selectedInterval, aggregators, queryParams);
		modelToRender.put("eventResultValues", graphCollection);


		if (isHighchartGraphLimitReached(graphCollection)) {
			modelToRender.put("warnAboutExceededPointsPerGraphLimit", true);
		}
		modelToRender.put("highChartsTurboThreshold", RESULT_DASHBOARD_MAX_POINTS_PER_SERIES);

		modelToRender.put("highChartLabels", labelToDataMap);
		modelToRender.put("markerShouldBeEnabled", false);

		//add / remove 5 Minutes 
		modelToRender.put('fromTimestampForHighChart', (timeFrame.getStart().toDate().getTime() - 300000))
		modelToRender.put('toTimestampForHighChart', (timeFrame.getEnd().toDate().getTime() + 300000))

		modelToRender.put("selectedCharttypeForHighchart", cmd.getSelectChartType());

	}

	/**
	 * <p>
	 * Checks if the maximum count of points per graph is exceeded.
	 * </p>
	 * <p><strong>Important: </strong> The current limit is 1000 points per graph: {@link http://api.highcharts.com/highcharts#plotOptions.series.turboThreshold} </p>
	 *
	 * @param graphCollection List of Highchart graphs, not <code>null</code>!
	 * @return <code>true</code> if the limit is exceeded,
	 *         <code>false</code> else.
	 */
	private boolean isHighchartGraphLimitReached(List<OsmChartGraph> graphCollection) {
		boolean returnValue = false;

		graphCollection.each { OsmChartGraph graph ->
			if (graph.getPoints().size() > RESULT_DASHBOARD_MAX_POINTS_PER_SERIES) {
				returnValue = true;
			}
		}
		return returnValue;
	}

	private Collection<AggregatorType> getAggregators(Collection<String> aggregatorNames) {

		Collection<AggregatorType> aggregators = []
		aggregatorNames.each { String name ->
			AggregatorType aggregator = AggregatorType.findByName(name);
			if (aggregator != null) {
				aggregators.add(aggregator);
			}
		}

		return aggregators;
	}

	/**
	 * <p>
	 * WARNING: This method is a duplicate of CsiDashboardController's 
	 * version.
	 * </p>
	 *
	 * Rounds a double according to CSV and Table requirements.
	 *
	 * @param valueToRound
	 *         The double value to round.
	 * @return The rounded value.
	 * @since IT-102 / copy since: IT-188
	 */
	static private double roundDouble(double valueToRound) {
		return new BigDecimal(valueToRound).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue();
	}

	/**
	 * <p>
	 * WARNING: This method is a duplicate of CsiDashboardController's 
	 * version.
	 * </p>
	 *
	 * <p>
	 * Performs a redirect with HTTP status code 303 (see other).
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
	 * @param actionNameToRedirectTo
	 *        The Name of the action to redirect to;
	 *        not <code>null</code>.
	 * @param urlParams
	 *        The parameters to add as query string;
	 *        not <code>null</code>.
	 *
	 * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3.4"
	 *      >http://tools.ietf.org/html/rfc2616#section-10.3.4</a>
	 *
	 * @since copy since: IT-188
	 */
	private void redirectWith303(String actionNameToRedirectTo, Map urlParams) {
		// There is a missing feature to do this:
		// http://jira.grails.org/browse/GRAILS-8829

		// Workaround based on:
		// http://fillinginthegaps.wordpress.com/2008/12/26/grails-301-moved-permanently-redirect/
		Map paramsWithoutGrailsActionNameOfOldAction = urlParams.findAll({ Map.Entry m -> !m.getKey().toString().startsWith('_action') });
		String uri = grailsLinkGenerator.link(action: actionNameToRedirectTo, params: paramsWithoutGrailsActionNameOfOldAction)
		response.setStatus(303)
		response.setHeader("Location", uri)
		render(status: 303)
	}

	/**
	 * <p>
	 * WARNING: This constant is a duplicate of CsiDashboardController's 
	 * version.
	 * </p>
	 *
	 * The {@link DateTimeFormat} used for CSV export and table view.
	 * @since copy since: IT-188
	 */
	private static
	final DateTimeFormatter CSV_TABLE_DATE_TIME_FORMAT = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss");

	/**
	 * <p>
	 * WARNING: This method is a duplicate of CsiDashboardController's 
	 * version.
	 * </p>
	 *
	 * <p>
	 * Converts the specified result values in the source map to a CSV
	 * corresponding to RFC 4180 written to specified {@link Writer}.
	 * </p>
	 *
	 * @param source
	 *         The result values a List of OsmChartGraph,
	 *         not <code>null</code>.
	 * @param target
	 *         The {@link Writer} to write CSV to,
	 *         not <code>null</code>.
	 * @param localeForNumberFormat
	 *         The locale used to format the numeric values,
	 *         not <code>null</code>.
	 *
	 * @throws IOException if write on {@code target} failed.
	 * @since copy since: IT-188
	 */
	private
	static void writeCSV(List<OsmChartGraph> source, Writer target, Locale localeForNumberFormat) throws IOException {
		NumberFormat valueFormat = NumberFormat.getNumberInstance(localeForNumberFormat);

		// Sort graph points by time
		TreeMapOfTreeMaps<Long, String, OsmChartPoint> pointsByGraphByTime = new TreeMapOfTreeMaps<Long, String, OsmChartPoint>();
		for (OsmChartGraph eachCSIValueEntry : source) {
			for (OsmChartPoint eachPoint : eachCSIValueEntry.getPoints()) {
				pointsByGraphByTime.getOrCreate(eachPoint.time).put(eachCSIValueEntry.getLabel(), eachPoint);
			}
		}

		CsvListWriter csvWriter = new CsvListWriter(
				target,
				new CsvPreference.Builder(CsvPreference.EXCEL_NORTH_EUROPE_PREFERENCE).useEncoder(new DefaultCsvEncoder()).build()
		)

		// Create CSV header:
		List<String> csvHeader = new LinkedList<String>();
		csvHeader.add('Zeitpunkt'); // TODO i18n?

		List<String> graphLabelsInOrderOfHeader = new LinkedList<String>();

		for (OsmChartGraph eachGraph : source) {
			csvHeader.add(eachGraph.getLabel());
			graphLabelsInOrderOfHeader.add(eachGraph.getLabel());
		}

		csvWriter.writeHeader(csvHeader.toArray(new String[csvHeader.size()]));

		for (Map.Entry<Long, TreeMap<String, OsmChartPoint>> eachPointByGraphOfTime : pointsByGraphByTime) {
			List<String> row = new LinkedList<String>();

			DateTime time = new DateTime(eachPointByGraphOfTime.getKey());
			row.add(CSV_TABLE_DATE_TIME_FORMAT.print(time));

			for (String eachGraphLabel : graphLabelsInOrderOfHeader) {
				OsmChartPoint point = eachPointByGraphOfTime.getValue().get(eachGraphLabel);
				if (point != null) {
					row.add(valueFormat.format(roundDouble(point.measuredValue)));
				} else {
					row.add("");
				}
			}

			csvWriter.writeRow(row);
		}

		csvWriter.flush();
	}

	/**
	 * <p>
	 * Creates a CSV based on the selection passed as {@link ShowAllCommand}.
	 * </p>
	 *
	 * @param cmd
	 *         The command with the users selections;
	 *         not <code>null</code>.
	 * @return nothing , immediately renders a CSV to response' output stream.
	 * @see <a href="http://tools.ietf.org/html/rfc4180">http://tools.ietf.org/html/rfc4180</a>
	 */
	public Map<String, Object> downloadCsv(ShowAllCommand cmd) {

		Map<String, Object> modelToRender = new HashMap<String, Object>();

		if (request.queryString && cmd.validate()) {
			fillWithMeasuredValueData(modelToRender, cmd);
			cmd.copyRequestDataToViewModelMap(modelToRender)
		} else {
			redirectWith303('showAll', params)
			return
		}

		String filename = modelToRender['aggrGroup'] + '_' + modelToRender['fromFormatted'] + '_to_' + modelToRender['toFormatted'] + '.csv';

		response.setHeader('Content-disposition', 'attachment; filename=' + filename);
		response.setContentType("text/csv;header=present;charset=UTF-8");

		Writer responseWriter = new OutputStreamWriter(response.getOutputStream());

		List<OsmChartGraph> csiValues = modelToRender['eventResultValues'];
		writeCSV(csiValues, responseWriter, RequestContextUtils.getLocale(request));

		response.getOutputStream().flush()
		response.sendError(200, 'OK');
		return null;
	}

	/**
	 * <p>
	 * Command of {@link EventResultDashboardController#showAll(ShowAllCommand)
	 *}.
	 * </p>
	 *
	 * <p>
	 * None of the properties will be <code>null</code> for a valid instance.
	 * Some collections might be empty depending on the {@link #aggrGroup}
	 * used.
	 * </p>
	 *
	 * <p>
	 * <em>DEV-Note:</em> This command uses auto-binding for type {@link Date}.
	 * To make this possible, you need a custom {@link PropertyEditor}.
	 * See class {@link CustomDateEditorRegistrar} for details. If try an
	 * auto-binding in a unit-test you need to register the class
	 * CustomDateEditorRegistrar with a code-block like:
	 * <pre>
	 * defineBeans {*     customPropertyEditorRegistrar(CustomDateEditorRegistrar)
	 *}* </pre>
	 * in the set-up of your test. For productive use you need to add
	 * <pre>
	 * beans = {*     customPropertyEditorRegistrar(CustomDateEditorRegistrar)
	 *}* </pre>
	 * to the config file {@code grails-app/conf/spring/resources.groovy}
	 * </p>
	 *
	 * @author mze , rhe
	 * @since IT-6
	 */
	@Validateable
	public static class ShowAllCommand {

		/**
		 * The selected start date.
		 *
		 * Please use {@link #getSelectedTimeFrame()}.
		 */
		Date from

		/**
		 * The selected end date.
		 *
		 * Please use {@link #getSelectedTimeFrame()}.
		 */
		Date to

		/**
		 * The selected start hour of date.
		 *
		 * Please use {@link #getSelectedTimeFrame()}.
		 */
		String fromHour

		/**
		 * The selected end hour of date.
		 *
		 * Please use {@link #getSelectedTimeFrame()}.
		 */
		String toHour

		/**
		 * The name of the {@link AggregatorType}.
		 *
		 * @deprecated Currently unused! TODO Check for usages, if not required: remove!
		 */
		@Deprecated
		String aggrGroup

		/**
		 * The time of the {@link MeasuredValueInterval}.
		 */
		Integer selectedInterval

		/**
		 * A predefined time frame.
		 */
		int selectedTimeFrameInterval = 259200

		/**
		 * The Selected chart type (line or point)
		 */
		Integer selectChartType;

		/**
		 * The database IDs of the selected {@linkplain JobGroup CSI groups}
		 * which are the systems measured for a CSI value 
		 */
		Collection<Long> selectedFolder = []

		/**
		 * The database IDs of the selected {@linkplain Page pages}
		 * which results to be shown.
		 *
		 * TODO rename to selectedPages
		 */
		Collection<Long> selectedPage = []

		/**
		 * The database IDs of the selected {@linkplain de.iteratec.osm.result.MeasuredEvent
		 * measured events} which results to be shown.
		 *
		 * These selections are only relevant if
		 * {@link #selectedAllMeasuredEvents} is evaluated to
		 * <code>false</code>.
		 */
		Collection<Long> selectedMeasuredEventIds = []

		/**
		 * User enforced the selection of all measured events.
		 * This selection <em>is not</em> reflected in
		 * {@link #selectedMeasuredEventIds} cause of URL length
		 * restrictions. If this flag is evaluated to
		 * <code>true</code>, the selections in
		 * {@link #selectedMeasuredEventIds} should be ignored.
		 */
		Boolean selectedAllMeasuredEvents = true

		/**
		 * The database IDs of the selected {@linkplain Browser
		 * browsers} which results to be shown.
		 *
		 * These selections are only relevant if
		 * {@link #selectedAllBrowsers} is evaluated to
		 * <code>false</code>.
		 */
		Collection<Long> selectedBrowsers = []

		/**
		 * User enforced the selection of all browsers.
		 * This selection <em>is not</em> reflected in
		 * {@link #selectedBrowsers} cause of URL length
		 * restrictions. If this flag is evaluated to
		 * <code>true</code>, the selections in
		 * {@link #selectedBrowsers} should be ignored.
		 */
		Boolean selectedAllBrowsers = true

		/**
		 * The database IDs of the selected {@linkplain Location
		 * locations} which results to be shown.
		 *
		 * These selections are only relevant if
		 * {@link #selectedAllLocations} is evaluated to
		 * <code>false</code>.
		 */
		Collection<Long> selectedLocations = []

		/**
		 * User enforced the selection of all locations.
		 * This selection <em>is not</em> reflected in
		 * {@link #selectedLocations} cause of URL length
		 * restrictions. If this flag is evaluated to
		 * <code>true</code>, the selections in
		 * {@link #selectedLocations} should be ignored.
		 */
		Boolean selectedAllLocations = true

		/**
		 * Database name of the selected {@link AggregatorType}, selected by the user.
		 * Determines wich {@link CachedView#CACHED} results should be shown.
		 */
		Collection<String> selectedAggrGroupValuesCached = []

		/**
		 * Database name of the selected {@link AggregatorType}, selected by the user.
		 * Determines wich {@link CachedView#UNCACHED} results should be shown.
		 */
		Collection<String> selectedAggrGroupValuesUnCached = []

		/**
		 * Lower bound for load-time-measurands. Values lower than this will be excluded from graphs.
		 */
		Integer trimBelowLoadTimes

		/**
		 * Upper bound for load-time-measurands. Values greater than this will be excluded from graphs.
		 */
		Integer trimAboveLoadTimes

		/**
		 * Lower bound for request-count-measurands. Values lower than this will be excluded from graphs.
		 */
		Integer trimBelowRequestCounts

		/**
		 * Upper bound for request-count-measurands. Values greater than this will be excluded from graphs.
		 */
		Integer trimAboveRequestCounts

		/**
		 * Lower bound for request-sizes-measurands. Values lower than this will be excluded from graphs.
		 */
		Integer trimBelowRequestSizes

		/**
		 * Upper bound for request-sizes-measurands. Values greater than this will be excluded from graphs.
		 */
		Integer trimAboveRequestSizes

		/**
		 * If the user has been warned about a potentially long processing
		 * time, did he overwrite the waring and really want to perform
		 * the request?
		 *
		 * A value of <code>true</code> indicates that overwrite, everything
		 * should be done as requested, <code>false</code> indicates that
		 * the user hasn't been warned before, so there is no overwrite.
		 */
		Boolean overwriteWarningAboutLongProcessingTime;

		/**
		 * Flag for manual debugging.
		 * Used for debugging highcharts-export-server, e.g.
		 */
		Boolean debug

		/**
		 * Whether or not the time of the start-date should be selected manually.
		 */
		Boolean setFromHour
		/**
		 * Whether or not the time of the start-date should be selected manually.
		 */
		Boolean setToHour

		/**
		 * Constraints needs to fit.
		 */
		static constraints = {
			from(nullable: true, validator: { Date currentFrom, ShowAllCommand cmd ->
				boolean manualTimeframe = cmd.selectedTimeFrameInterval == 0
				if (manualTimeframe && currentFrom == null) return ['nullWithManualSelection']
			})
			to(nullable: true, validator: { Date currentTo, ShowAllCommand cmd ->
				boolean manualTimeframe = cmd.selectedTimeFrameInterval == 0
				if (manualTimeframe && currentTo == null) return ['nullWithManualSelection']
				else if (manualTimeframe && currentTo != null && cmd.from != null && currentTo.before(cmd.from)) return ['beforeFromDate']
			})
			fromHour(nullable: true, validator: { String currentFromHour, ShowAllCommand cmd ->
				boolean manualTimeframe = cmd.selectedTimeFrameInterval == 0
				if (manualTimeframe && currentFromHour == null) return ['nullWithManualSelection']
			})
			toHour(nullable: true, validator: { String currentToHour, ShowAllCommand cmd ->
				boolean manualTimeframe = cmd.selectedTimeFrameInterval == 0
				if (manualTimeframe && currentToHour == null) {
					return ['nullWithManualSelection']
				} else if (manualTimeframe && cmd.from != null && cmd.to != null && cmd.from.equals(cmd.to) && cmd.fromHour != null && currentToHour != null) {
					DateTime firstDayWithFromDaytime = getFirstDayWithTime(cmd.fromHour)
					DateTime firstDayWithToDaytime = getFirstDayWithTime(currentToHour)
					if (!firstDayWithToDaytime.isAfter(firstDayWithFromDaytime)) return ['inCombinationWithDateBeforeFrom']
				}
			})
			selectedAggrGroupValuesCached(nullable: false, validator: { Collection<String> selectedCheckedAggregators, ShowAllCommand cmd ->
				cmd.selectedAggrGroupValuesCached.size() > 0 || cmd.selectedAggrGroupValuesUnCached.size() > 0
			})
			selectedAllMeasuredEvents(nullable: true)
			selectedAllBrowsers(nullable: true)
			selectedAllLocations(nullable: true)

			selectedFolder(nullable: false, validator: { Collection currentCollection, ShowAllCommand cmd ->
				!currentCollection.isEmpty()
			})
			selectedPage(nullable: false, validator: { Collection currentCollection, ShowAllCommand cmd ->
				!currentCollection.isEmpty()
			})
			selectedBrowsers(nullable: false, validator: { Collection currentCollection, ShowAllCommand cmd ->
				(cmd.selectedAllBrowsers || !currentCollection.isEmpty())
			})
			selectedMeasuredEventIds(nullable: false, validator: { Collection currentCollection, ShowAllCommand cmd ->
				(cmd.selectedAllMeasuredEvents || !currentCollection.isEmpty())
			})
			selectedLocations(nullable: false, validator: { Collection currentCollection, ShowAllCommand cmd ->
				(cmd.selectedAllLocations || !currentCollection.isEmpty())
			})

			overwriteWarningAboutLongProcessingTime(nullable: true)

			//TODO: validators for trimAbove's and -Below's

		}

		static transients = ['selectedTimeFrame']

		/**
		 * <p>
		 * Returns the selected time frame as {@link Interval}.
		 * That is the interval from {@link #from} / {@link #fromHour} to {@link #to} / {@link #toHour} if {@link #selectedTimeFrameInterval} is 0 (that means manual).
		 * If {@link #selectedTimeFrameInterval} is greater 0 the returned time frame is now minus {@link #selectedTimeFrameInterval} minutes to now.
		 * </p>
		 *
		 * @return not <code>null</code>.
		 * @throws IllegalStateException
		 *         if called on an invalid instance.
		 */
		public Interval getSelectedTimeFrame() throws IllegalStateException {
			if (!this.validate()) {
				throw new IllegalStateException('A time frame is not available from an invalid command.')
			}

			DateTime start
			DateTime end

			Boolean manualTimeframe = this.selectedTimeFrameInterval == 0
			if (manualTimeframe && fromHour && toHour) {

				DateTime firstDayWithFromHourAsDaytime = getFirstDayWithTime(fromHour)
				DateTime firstDayWithToHourAsDaytime = getFirstDayWithTime(toHour)

				start = new DateTime(this.from.getTime())
						.withTime(
						firstDayWithFromHourAsDaytime.getHourOfDay(),
						firstDayWithFromHourAsDaytime.getMinuteOfHour(),
						0, 0
				)
				end = new DateTime(this.to.getTime())
						.withTime(
						firstDayWithToHourAsDaytime.getHourOfDay(),
						firstDayWithToHourAsDaytime.getMinuteOfHour(),
						59, 999
				)

			} else {

				end = new DateTime()
				start = end.minusSeconds(this.selectedTimeFrameInterval)

			}

			return new Interval(start, end);
		}

		/**
		 * Returns a {@link DateTime} of the first day in unix-epoch with daytime respective param timeWithOrWithoutMeridian. 
		 * @param timeWithOrWithoutMeridian
		 * 		The format can be with or without meridian (e.g. "04:45", "16:12" without or "02:00 AM", "11:23 PM" with meridian)
		 * @return A {@link DateTime} of the first day in unix-epoch with daytime respective param timeWithOrWithoutMeridian.
		 * @throws IllegalStateException If timeWithOrWithoutMeridian is in wrong format.
		 */
		public static DateTime getFirstDayWithTime(String timeWithOrWithoutMeridian) throws IllegalStateException {

			Pattern regexWithMeridian = ~/\d{1,2}:\d\d [AP]M/
			Pattern regexWithoutMeridian = ~/\d{1,2}:\d\d/
			String dateFormatString

			if (timeWithOrWithoutMeridian ==~ regexWithMeridian) dateFormatString = "dd.MM.yyyy hh:mm"
			else if (timeWithOrWithoutMeridian ==~ regexWithoutMeridian) dateFormatString = "dd.MM.yyyy HH:mm"
			else throw new IllegalStateException("Wrong format of time: ${timeWithOrWithoutMeridian}")

			DateTimeFormatter fmt = DateTimeFormat.forPattern(dateFormatString)
			return fmt.parseDateTime("01.01.1970 ${timeWithOrWithoutMeridian}")

		}

		/**
		 * <p>
		 * Copies all request data to the specified map. This operation does
		 * not care about the validation status of this instance.
		 * For missing values the defaults are inserted.
		 * </p>
		 *
		 * @param viewModelToCopyTo
		 *         The {@link Map} the request data contained in this command
		 *         object should be copied to. The map must be modifiable.
		 *         Previously contained data will be overwritten.
		 *         The argument might not be <code>null</code>.
		 */
		public void copyRequestDataToViewModelMap(Map<String, Object> viewModelToCopyTo) {

			viewModelToCopyTo.put('selectedTimeFrameInterval', this.selectedTimeFrameInterval)
			viewModelToCopyTo.put('selectedInterval', this.selectedInterval ?: MeasuredValueInterval.RAW)

			viewModelToCopyTo.put('selectedFolder', this.selectedFolder)
			viewModelToCopyTo.put('selectedPage', this.selectedPage)

			viewModelToCopyTo.put('selectedAllMeasuredEvents', this.selectedAllMeasuredEvents)
			viewModelToCopyTo.put('selectedMeasuredEventIds', this.selectedMeasuredEventIds)

			viewModelToCopyTo.put('selectedAllBrowsers', this.selectedAllBrowsers)
			viewModelToCopyTo.put('selectedBrowsers', this.selectedBrowsers)

			viewModelToCopyTo.put('selectedAllLocations', this.selectedAllLocations)
			viewModelToCopyTo.put('selectedLocations', this.selectedLocations)

			CustomDateEditor dateEditor = CustomDateEditorRegistrar.createCustomDateEditor();

			dateEditor.setValue(this.from)
			viewModelToCopyTo.put('from', dateEditor.getAsText())
			if (!this.fromHour.is(null)) {
				viewModelToCopyTo.put('fromHour', this.fromHour)
			}

			dateEditor.setValue(this.to)
			viewModelToCopyTo.put('to', dateEditor.getAsText())
			if (!this.toHour.is(null)) {
				viewModelToCopyTo.put('toHour', this.toHour)
			}

			viewModelToCopyTo.put("selectedChartType", this.selectChartType ? POINT_CHART_SELECTION : LINE_CHART_SELECTION);

			viewModelToCopyTo.put('selectedAggrGroupValuesCached', this.selectedAggrGroupValuesCached)
			viewModelToCopyTo.put('selectedAggrGroupValuesUnCached', this.selectedAggrGroupValuesUnCached)

			viewModelToCopyTo.put('trimBelowLoadTimes', this.trimBelowLoadTimes)
			viewModelToCopyTo.put('trimAboveLoadTimes', this.trimAboveLoadTimes)
			viewModelToCopyTo.put('trimBelowRequestCounts', this.trimBelowRequestCounts)
			viewModelToCopyTo.put('trimAboveRequestCounts', this.trimAboveRequestCounts)
			viewModelToCopyTo.put('trimBelowRequestSizes', this.trimBelowRequestSizes)
			viewModelToCopyTo.put('trimAboveRequestSizes', this.trimAboveRequestSizes)
			viewModelToCopyTo.put('debug', this.debug ?: false)
			viewModelToCopyTo.put('setFromHour', this.setFromHour)
			viewModelToCopyTo.put('setToHour', this.setToHour)

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
		 */
		public MvQueryParams createMvQueryParams() throws IllegalStateException {
			if (!this.validate()) {
				throw new IllegalStateException('Query params are not available from an invalid command.')
			}

			ErQueryParams result = new ErQueryParams();

			result.jobGroupIds.addAll(this.selectedFolder);

			if (!this.selectedAllMeasuredEvents) {
				result.measuredEventIds.addAll(this.selectedMeasuredEventIds);
			}

			result.pageIds.addAll(this.selectedPage);

			if (!this.selectedAllBrowsers) {
				result.browserIds.addAll(this.selectedBrowsers);
			}

			if (!this.selectedAllLocations) {
				result.locationIds.addAll(this.selectedLocations);
			}
			if (this.trimBelowLoadTimes) {
				result.minLoadTimeInMillisecs = this.trimBelowLoadTimes
			}
			if (this.trimAboveLoadTimes) {
				result.maxLoadTimeInMillisecs = this.trimAboveLoadTimes
			}
			if (this.trimBelowRequestCounts) {
				result.minRequestCount = this.trimBelowRequestCounts
			}
			if (this.trimAboveRequestCounts) {
				result.maxRequestCount = this.trimAboveRequestCounts
			}
			if (this.trimBelowRequestSizes) {
				result.minRequestSizeInBytes = this.trimBelowRequestSizes * 1000
			}
			if (this.trimAboveRequestSizes) {
				result.maxRequestSizeInBytes = this.trimAboveRequestSizes * 1000
			}

			return result;
		}
	}

	/**
	 * <p>
	 * Tests weather the UI should warn the user about an expected long
	 * execution time for calculations on a time frame.
	 * </p>
	 *
	 * @param timeFrame
	 *         The time frame to guess weather a user should be warned
	 *         about potently very long calculation time;
	 *         not <code>null</code>.
	 * @param countOfSelectedAggregatorTypes
	 *         The number of selected aggregatorTypes; >= 1.
	 * @param countOfSelectedSystems
	 *         The number of selected systems / {@link JobGroup}s; >= 1.
	 * @param countOfSelectedPages
	 *         The number of selected pages; >= 1.
	 * @param countOfSelectedBrowser
	 *         The number of selected browser; >= 1.
	 *
	 * @return <code>true</code> if the user should be warned,
	 *         <code>false</code> else.
	 * @since IT-157
	 */
	public boolean shouldWarnAboutLongProcessingTime(
			Interval timeFrame,
			int interval,
			int countOfSelectedAggregatorTypes,
			int countOfSelectedSystems,
			int countOfSelectedBrowser,
			int countOfSelectedPages) {
		int minutesInTimeFrame = new Duration(timeFrame.getStart(), timeFrame.getEnd()).getStandardMinutes();

		long expectedPointsOfEachGraph;
		if (interval == MeasuredValueInterval.RAW || interval == 0 || interval == null) {
			//50 results per Day
			expectedPointsOfEachGraph = Math.round(minutesInTimeFrame / 60 / 24 * EXPECTED_RESULTS_PER_DAY);
		} else {
			expectedPointsOfEachGraph = Math.round(minutesInTimeFrame / interval);
		}

		if (expectedPointsOfEachGraph > 1000) {
			return true;
		} else {

			long expectedCountOfGraphs = countOfSelectedAggregatorTypes * countOfSelectedSystems * countOfSelectedPages * countOfSelectedBrowser;
			long expectedTotalNumberOfPoints = expectedCountOfGraphs * expectedPointsOfEachGraph;

			return expectedTotalNumberOfPoints > 10000;
		}
	}

	/**
	 * <p>
	 * Constructs the static view data of the {@link #showAll(ShowAllCommand)}
	 * view as {@link Map}.
	 * </p>
	 *
	 * <p>
	 * This map does always contain all available data for selections, previous
	 * selections are not considered.
	 * </p>
	 *
	 * @return A Map containing the static view data which are accessible
	 *         through corresponding keys. The Map is modifiable to add
	 *         further data. Subsequent calls will never return the same
	 *         instance.
	 */
	public Map<String, Object> constructStaticViewDataOfShowAll() {
		Map<String, Object> result = [:]

		// AggregatorTypes
		result.put('aggrGroupLabels', AGGREGATOR_GROUP_LABELS)
		result.put('aggrGroupValuesCached', AGGREGATOR_GROUP_VALUES.get(CachedView.CACHED))
		result.put('aggrGroupValuesUnCached', AGGREGATOR_GROUP_VALUES.get(CachedView.UNCACHED))

		// Intervals
		result.put('measuredValueIntervals', measuredValueIntervals)

		// JobGroups
		List<JobGroup> jobGroups = eventResultDashboardService.getAllJobGroups();
		result.put('folders', jobGroups)

		// Pages
		List<Page> pages = eventResultDashboardService.getAllPages();
		result.put('pages', pages)

		// MeasuredEvents
		List<MeasuredEvent> measuredEvents = eventResultDashboardService.getAllMeasuredEvents();
		result.put('measuredEvents', measuredEvents)

		// Browsers
		List<Browser> browsers = eventResultDashboardService.getAllBrowser();
		result.put('browsers', browsers)

		// Locations
		List<Location> locations = eventResultDashboardService.getAllLocations()
		result.put('locations', locations)

		// JavaScript-Utility-Stuff:
		result.put("dateFormat", DATE_FORMAT_STRING)
		result.put("weekStart", MONDAY_WEEKSTART)

		// --- Map<PageID, Set<MeasuredEventID>> for fast view filtering:
		Map<Long, Set<Long>> eventsOfPages = new HashMap<Long, Set<Long>>()
		for (Page eachPage : pages) {
			Set<Long> eventIds = new HashSet<Long>();

			Collection<Long> ids = measuredEvents.findResults {
				it.testedPage.getId() == eachPage.getId() ? it.getId() : null
			}
			if (!ids.isEmpty()) {
				eventIds.addAll(ids);
			}

			eventsOfPages.put(eachPage.getId(), eventIds);
		}
		result.put('eventsOfPages', eventsOfPages);

		// --- Map<BrowserID, Set<LocationID>> for fast view filtering:
		Map<Long, Set<Long>> locationsOfBrowsers = new HashMap<Long, Set<Long>>()
		for (Browser eachBrowser : browsers) {
			Set<Long> locationIds = new HashSet<Long>();

			Collection<Long> ids = locations.findResults {
				it.browser.getId() == eachBrowser.getId() ? it.getId() : null
			}
			if (!ids.isEmpty()) {
				locationIds.addAll(ids);
			}

			locationsOfBrowsers.put(eachBrowser.getId(), locationIds);
		}
		result.put('locationsOfBrowsers', locationsOfBrowsers);

		result.put("selectedChartType", 0);
		result.put("warnAboutExceededPointsPerGraphLimit", false);
		result.put("chartRenderingLibrary", cookieBasedSettingsService.getChartingLibraryToUse())

		// Done! :)
		return result;
	}

}
