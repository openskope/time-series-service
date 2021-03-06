package org.openskope.timeseries.service;

import org.openskope.timeseries.controller.InvalidArgumentException;
import org.openskope.timeseries.controller.InvalidDataException;
import org.openskope.timeseries.model.IndexRange;
import org.openskope.timeseries.model.TimeScale;
import org.openskope.timeseries.model.TimeseriesRequest;
import org.openskope.timeseries.model.TimeseriesResponse;
import org.openskope.util.ProcessRunner;
import org.openskope.util.StreamSink;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriTemplate;

import java.io.File;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TimeseriesService implements InitializingBean {
    
	@Value("${TIMESERIES_DATA_PATH_TEMPLATE}") public String valuesDataPath;
	@Value("${TIMESERIES_UNCERTAINTY_PATH_TEMPLATE}") public String uncertaintyDataPath;
	@Value("${TIMESERIES_DATA_FILE_EXTENSIONS}") public String timeseriesDatafileExtensions;
	@Value("${TIMESERIES_GDALLOCATIONINFO_COMMAND}") public String gdallocationinfoCommand;
	@Value("${TIMESERIES_ZONALINFO_COMMAND}") public String zonalinfoCommand;
	@Value("${TIMESERIES_MAX_PROCESSING_TIME}") public String maxTimeoutSetting;

    private UriTemplate valuesPathTemplate = null;
    private UriTemplate uncertaintyPathTemplate = null;
    private String[] extensions;
    private Map<String, Number> nodataSettingForFile = new HashMap<String,Number>();
    private Map<String, String> uriVariables = new HashMap<String,String>();
    private ObjectMapper mapper = new ObjectMapper();
    private long maxTimeoutMilliseconds;
    private long timeoutMilliseconds;
    private Number nodataValue;
    
    public void afterPropertiesSet() {
    			
    	valuesPathTemplate = new UriTemplate(valuesDataPath);
    	
    	if (uncertaintyDataPath.trim().length() > 0) {
    		uncertaintyPathTemplate = new UriTemplate(uncertaintyDataPath);
    	}
    	    	
    	String[] customExtensionArray = {};
    	if (timeseriesDatafileExtensions.trim().length() > 0) {
    		customExtensionArray = timeseriesDatafileExtensions.split("\\s+");
    	}
    	extensions = new String[1 + customExtensionArray.length];
    	extensions[0] = "";
    	int i = 1;
    	for (String customExtension : customExtensionArray) {
    		extensions[i++] = customExtension;
    	}
    	
    	maxTimeoutMilliseconds = Long.parseLong(maxTimeoutSetting);
    }

	public TimeseriesResponse getTimeseries(TimeseriesRequest request) throws Exception {
		
		Long requestedTimeout = request.getTimeout();
		if (requestedTimeout != null && requestedTimeout < maxTimeoutMilliseconds) {
			timeoutMilliseconds = requestedTimeout;
		} else {
			timeoutMilliseconds = maxTimeoutMilliseconds;
		}

        TimeScale timeScale = new TimeScale(request.getTimeResolution(), request.getTimeZero());

        File dataFile = getDataFile(valuesPathTemplate, request.getDatasetId(), request.getVariableName());
        File uncertaintiesFile = getDataFile(uncertaintyPathTemplate, request.getDatasetId(), request.getVariableName());

        if (dataFile == null) {
        	throw new InvalidArgumentException(
    			"Data file for dataset '" + request.getDatasetId() +
    			"', variable '" + request.getVariableName() + 
    			"' does not exist on timeseries server."
			);
        }
        
        nodataValue = getNodataValue(request.getNodata(), dataFile);        
        
        String[] fullTimeSeries = getFullTimeseries(request, dataFile);
        IndexRange responseRange = timeScale.getResponseIndexRange(request.getStart(), request.getEnd(), fullTimeSeries.length);
        Number[] valuesInRequestedRange = getRangeOfStringValuesAsNumbers(fullTimeSeries, responseRange.startIndex, responseRange.endIndex, nodataValue);      
        boolean containsNodata = containsNodataValue(valuesInRequestedRange);
        Number[] uncertainties = getUncertaintiesInRange(request, uncertaintiesFile, responseRange.startIndex, responseRange.endIndex);
                
        Number[] values = null;
        Number[] lowerBounds = null;
        Number[] upperBounds = null;
        if ((request.getArray() == null || request.getArray())) {
        	values = valuesInRequestedRange;
        	if (uncertainties != null) {
        		upperBounds = addArrays(values, uncertainties, +1);
        		lowerBounds = addArrays(values, uncertainties, -1);
        	}
        }

        String csv = null;
        
        if (request.getCsv() == null || request.getCsv()) {
        	if (uncertainties != null) {
        		csv = getTableWithUncertainties(responseRange, timeScale, request.getVariableName(), valuesInRequestedRange, upperBounds, lowerBounds);
        	} else {
        		csv = getTable(responseRange, timeScale, request.getVariableName(), valuesInRequestedRange);
        	}
        }
		                
        return new TimeseriesResponse(
        		request.getDatasetId(),
        		request.getVariableName(),
        		request.getBoundaryGeometry(),
        		timeScale.getTimeForIndex(responseRange.startIndex),
        		timeScale.getTimeForIndex(responseRange.endIndex),
        		responseRange.startIndex,
        		responseRange.endIndex,
        		values,
        		upperBounds,
        		lowerBounds,
        		csv,
        		nodataValue,
        		containsNodata
    		);
	}
	
	private Number[] getUncertaintiesInRange(TimeseriesRequest request, File uncertaintiesFile, int startIndex, int endIndex) throws Exception {

		if (uncertaintiesFile != null) {
	        String[] allUncertainties = getFullTimeseries(request, uncertaintiesFile);
	        int indexOfLastUncertainty = allUncertainties.length -1;
	        if (startIndex > indexOfLastUncertainty || endIndex > indexOfLastUncertainty) {
	        	throw new InvalidDataException("Uncertainty file does not cover the entire requested timeseries");
	        }
	        return getRangeOfStringValuesAsNumbers(allUncertainties, startIndex, endIndex, null);        			
		}
		
		return null;
	}
	
	private Number[] addArrays(Number[] values, Number[] delta, int sign) {
		Number[] sum = new Number[values.length];
		for (int i = 0; i < values.length; ++i) {
			if (values[i] == null ) {
				sum[i] = null;
			} else { 
				sum[i] = values[i].doubleValue() + sign * delta[i].doubleValue();
			}
		}
		return sum;
	}
	
	private String[] getFullTimeseries(TimeseriesRequest request, File dataFile) throws Exception {
		if (request.getBoundaryGeometryType().equalsIgnoreCase("POINT")) {
			return runGdalLocationInfo(dataFile, request.getLongitude(), request.getLatitude());
		} else {
			return runZonalInfo(dataFile, request);
		}
	}
	
	private File getDataFile(UriTemplate pathTemplate, String datasetId, String variableName) {
		if (pathTemplate != null) {
	        uriVariables.put("datasetId", datasetId);
	        uriVariables.put("variableName", variableName);
			URI datafileBaseUri = pathTemplate.expand(uriVariables);
			for (String extension : extensions) {
				File file = new File(datafileBaseUri.getPath() + extension);
				if (file.exists()) {
					return file;
				}
			}
		}
		return null;
	}
	
	private Number getNodataValue(String requestNodata, File dataFile) {
		Number nodata;
		if (requestNodata == null || requestNodata.equalsIgnoreCase("detect")) {
			nodata = detectNodataMetadata(dataFile);
		} else if (requestNodata.equalsIgnoreCase("ignore")) {
			nodata = null;
		} else {
			try {
				nodata = Double.parseDouble(requestNodata);
			} catch (Exception e) {
	        	throw new InvalidArgumentException("nodata", requestNodata);
			}
		}
		return nodata;
	}

	private Number detectNodataMetadata(File dataFile) {
		
		String dataFilePath = dataFile.getAbsolutePath();
		Number nodataValueFromFile = nodataSettingForFile.get(dataFilePath);

		if (nodataValueFromFile == null) {
        
			String commandLine = String.format("gdalinfo %s ", dataFilePath);
	        System.out.println(commandLine);
	
	        String gdalinfoOutput;
	        try { 
		        StreamSink streams[] = ProcessRunner.run(commandLine, "", new String[0], null, maxTimeoutMilliseconds);
		        gdalinfoOutput = streams[0].toString();
	        } catch(Exception e) {
	        	return null;
	        }
	        
	        String nodataValueString = null;
	        Pattern pattern = Pattern.compile("NoData Value=(((-?[0-9.]+))|(nan))");
	        Matcher matcher = pattern.matcher(gdalinfoOutput);
	        if (matcher.find()) {
	        	nodataValueString = matcher.group(1);
	        	nodataValueFromFile = nodataValueString.equals("nan") ? Double.NaN : parseIntegerOrDouble(nodataValueString, null);
	        	nodataSettingForFile.put(dataFilePath, nodataValueFromFile);
	        }
		}
		
    	return nodataValueFromFile;
	}
	
	private boolean valueIsNodata(Number value) {

		if (nodataValue == null) return false;
		if (value == null) return true;
		
		// check for NaN indicating NODATA if NODATA is specified to be Double.NAN
		if (nodataValue instanceof Double && (Double.isNaN((double) nodataValue))) {
			return Double.isNaN(value.doubleValue());
		} else {
			return (value.intValue() == nodataValue.intValue());
		}
	}
	
	private boolean containsNodataValue(Number[] values) {
		
		// data contains no NODATA values if NODATA value is not specified for data set
		if (nodataValue == null) return false;
				
		for (Number n : values) {
			if (valueIsNodata(n)) return true;
		}

		return false;
	}
	
	private String[] runGdalLocationInfo(File dataFile, double longitude, double latitude) throws Exception {
        String commandLine = String.format(
                "%s -valonly -geoloc %s %f %f", gdallocationinfoCommand, dataFile.getAbsolutePath(), longitude, latitude);
        System.out.println(commandLine);
        StreamSink streams[] = ProcessRunner.run(commandLine, "", new String[0], null, timeoutMilliseconds);
        String[] stdoutTokens = streams[0].toString().split("\\s+");
        if (stdoutTokens.length == 0) {
        	throw new InvalidArgumentException("Coordinates are outside region covered by the dataset");
        }
        return stdoutTokens;
	}
	
	private String[] runZonalInfo(File dataFile, TimeseriesRequest request) throws Exception {
        String commandLine = String.format(
                "%s --valonly --geometry - %s", zonalinfoCommand, dataFile.getAbsolutePath());
        System.out.println(commandLine);
        String stdin = mapper.writeValueAsString(request.getBoundaryGeometry());
        System.out.println(stdin);
        StreamSink streams[] = ProcessRunner.run(commandLine, mapper.writeValueAsString(request.getBoundaryGeometry()), new String[0], null, timeoutMilliseconds);
        String stderr = streams[1].toString();
        
        if (stderr.length() > 0) {
    		if (stderr.contains("Input shapes do not overlap raster.")) {
    			throw new InvalidArgumentException("The selected area does not overlap the region covered by the dataset");
    		} else {
    			throw new InvalidArgumentException(stderr);
    		}
        }
        	
        return streams[0].toString().split("\\s+");
	}
	
	public Number[] getRangeOfStringValuesAsNumbers(String[] strings, int start, int end, Number nodataValue) {
		Number[] numbers = new Number[end - start + 1];
		for (int si = 0, ii = 0; si < strings.length; ++si) {
			if (si >= start && si <= end) {
				numbers[ii++] = parseIntegerOrDouble(strings[si], nodataValue);
			}
		}
		return numbers;
	}
	
	public Number parseIntegerOrDouble(String s, Number nodataValue) {
		
		if (s.equalsIgnoreCase("nan")) {
			if (nodataValue != null && Double.isNaN((double)nodataValue)) {
				return null;
			} else {
	        	throw new InvalidDataException("Timeseries values contains NaN");
			}
		}
		
		Number value = null;
		
        if (s.contains(".")) {
			value = Double.valueOf(s);
		} else {
			value = Integer.valueOf(s);
		}
        
        if (valueIsNodata(value)) {
        	return null;
        } else {
        	return value;
    	}
	}
	
	private String getTableValue(Number value) {
		if (valueIsNodata(value)) {
			return "";
		} else {
        	String format = (value instanceof Double) ? "%.6g" : "%d";
    		return String.format(format, value);
		}
	}
	
	public String getTable(IndexRange responseRange, TimeScale timeScale, String variableName, Number[] values) throws Exception {
		StringBuffer buffer = new StringBuffer();
        buffer.append(timeScale + ", " + variableName + "\n");
        for (int i = 0; i < values.length; ++i) {
    		buffer.append(String.format("%s,%s\n", 
    						timeScale.getTimeForIndex(i + responseRange.startIndex), 
    						getTableValue(values[i]))
			);
        }
        return buffer.toString();
	}

	public String getTableWithUncertainties(IndexRange responseRange, TimeScale timeScale, String variableName, 
			Number[] values,Number[] upperBounds, Number[] lowerBounds) throws Exception {
		StringBuffer buffer = new StringBuffer();
        buffer.append(timeScale + ", " + variableName + ", range -, range +\n");
        for (int i = 0; i < values.length; ++i) {
        	String format = (values[i] instanceof Double) ? "%s, %.6g, %.6g, %.6g\n" : "%s, %d, %.6g, %.6g\n";
    		buffer.append(String.format(
    						"%s,%s,%s,%s\n", 
    						timeScale.getTimeForIndex(i + responseRange.startIndex), 
    						getTableValue(values[i]),
							getTableValue(lowerBounds[i]),
							getTableValue(upperBounds[i]))
			);
        }
        return buffer.toString();
	}
}