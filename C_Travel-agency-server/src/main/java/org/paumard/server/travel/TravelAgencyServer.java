package org.paumard.server.travel;

import io.helidon.config.Config;
import io.helidon.common.uri.UriInfo;
import io.helidon.http.Status;
import io.helidon.http.media.MediaContext;
import io.helidon.http.media.jsonb.JsonbSupport;
import io.helidon.http.media.jsonp.JsonpSupport;
import io.helidon.webclient.api.ClientUri;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.staticcontent.StaticContentFeature;
import org.paumard.server.travel.model.city.Cities;
import org.paumard.server.travel.model.city.City;
import org.paumard.server.travel.model.company.Companies;
import org.paumard.server.travel.model.company.Company;
import org.paumard.server.travel.model.flight.Flight;
import org.paumard.server.travel.model.weather.Weather;
import org.paumard.server.travel.model.weather.WeatherAgencies;
import org.paumard.server.travel.model.weather.WeatherAgency;
import org.paumard.server.travel.response.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@SuppressWarnings("preview")
public class TravelAgencyServer {

    public record TravelRequest(String from, String to) {}
    public record QueryFlight(City from, City to) {}

    private static QueryFlight queriedFlightFrom(ServerRequest request) {
        var travelRequest = request.content().as(TravelRequest.class);
        var cityFrom = Cities.byName(travelRequest.from());
        var destinationCity = Cities.byName(travelRequest.to());
        var flight = new QueryFlight(cityFrom, destinationCity);
        return flight;
    }

    static Callable<CompanyResponse>
    companyQuery(WebClient companyClient, Company company, QueryFlight flight) {
        return () -> {
            try (var response = companyClient
                    .post("/company/" + company.tag())
                    .submit(flight)) {

                var companyServerResponse = CompanyServerResponse.of(response);
                return switch (companyServerResponse) {
                    case CompanyServerResponse.SimpleFlight(
                            Flight.SimpleFlight simpleFlight, int price) ->
                            new CompanyResponse.PricedSimpleFlight(
                                    company, simpleFlight, price);
                    case CompanyServerResponse.MultilegFlight(
                            Flight.MultilegFlight multiLegFlight, int price) ->
                            new CompanyResponse.PricedMultilegFlight(
                                    company, multiLegFlight, price);
                    case CompanyServerResponse.NoFlight(String message) ->
                            new CompanyResponse.NoFlight(company, message);
                    case CompanyServerResponse.Error(String message) ->
                            new CompanyResponse.Error(company, message);
                };
            }
        };
    }

    static Callable<WeatherResponse>
    weatherQuery(WebClient weatherClient, WeatherAgency agency, City city) {
        return () -> {
            try (var response = weatherClient
                    .post("/weather/" + agency.tag())
                    .submit(city)) {
                if (response.status() == Status.OK_200) {
                    var weather = response.as(Weather.class);
                    return new WeatherResponse.Weather(agency, weather.weather());
                } else {
                    return new WeatherResponse.NoWeather(agency);
                }
            }
        };
    }

    static CompanyResponse
    queryCompanyServer(ClientUri clientURI, QueryFlight queryFlight) {
        try (var companyScope =
                     StructuredTaskScope.<CompanyResponse, Void>open(
                             StructuredTaskScope.Joiner.awaitAll())) {
            WebClient companyClient = WebClient.builder()
                    .baseUri(clientURI).build();
            var companySubtasks = Companies.companies()
                    .stream()
                    .map(company ->
                            companyScope.fork(companyQuery(companyClient, company, queryFlight)))
                    .toList();

            companyScope.join();

            var bestFlightOpt = companySubtasks.stream()
                    .filter(task -> task.state() == StructuredTaskScope.Subtask.State.SUCCESS)
                    .map(StructuredTaskScope.Subtask::get)
                    .filter(CompanyResponse.Priced.class::isInstance)
                    .map(CompanyResponse.Priced.class::cast)
                    .min(Comparator.comparingInt(CompanyResponse.Priced::price));
            if (bestFlightOpt.isPresent()) {
                return bestFlightOpt.orElseThrow();
            } else {
                return new CompanyResponse.NoFlightFromAnyCompany("No Flight found");
            }
        } catch (InterruptedException e) {
            return new CompanyResponse.NoFlightFromAnyCompany("Process interrupted: " + e.getMessage());
        }
    }

    static WeatherResponse
    queryWeatherServer(ClientUri weatherServerUri, City destinationCity)
            throws InterruptedException {
        try (var weatherScope = StructuredTaskScope.<WeatherResponse, WeatherResponse>open(
                StructuredTaskScope.Joiner.anySuccessfulResultOrThrow())) {
            WebClient weatherClient = WebClient.builder()
                    .baseUri(weatherServerUri).build();
            WeatherAgencies.weatherAgencies()
                    .forEach(weatherAgency -> weatherScope.fork(weatherQuery(weatherClient, weatherAgency, destinationCity)));
            return weatherScope.join();
        }
    }

    static TravelResponse
    buildResponse(CompanyResponse companyResponse, WeatherResponse weatherResponse) {
        return switch (companyResponse) {
            case CompanyResponse.PricedSimpleFlight(
                    Company company, Flight.SimpleFlight simpleFlight, int price) ->
                    switch (weatherResponse) {
                        case WeatherResponse.Weather(
                                WeatherAgency agency, String weather) ->
                                new TravelResponse.SimpleFlightWeather(
                                        company, simpleFlight, price,
                                        new Weather(agency.name(), weather));
                        case WeatherResponse.NoWeather _, WeatherResponse.WeatherTimeout _ ->
                                new TravelResponse.SimpleFlightNoWeather(
                                        company, simpleFlight, price);
                    };
            case CompanyResponse.PricedMultilegFlight(
                    Company company, Flight.MultilegFlight multilegFlight, int price) ->
                    switch (weatherResponse) {
                        case WeatherResponse.Weather(WeatherAgency agency, String weather) ->
                                new TravelResponse.MultilegFlightWeather(
                                        company, multilegFlight, price,
                                        new Weather(agency.name(), weather));
                        case WeatherResponse.NoWeather _, WeatherResponse.WeatherTimeout _ ->
                                new TravelResponse.MultilegFlightNoWeather(
                                        company, multilegFlight, price);
                    };
            case CompanyResponse.Failed error -> new TravelResponse.NoFlight(error.message());
        };
    }

    void main() throws UnknownHostException {

        Cities.readCitiesFrom("us-cities.txt");
        Companies.readCompaniesFrom("companies.txt");
        WeatherAgencies.readWeatherAgenciesFrom("weather-agencies.txt");

        var properties = readPropertiesFrom("server.properties");
        var WEATHER_SERVER_URI = createWeatherServerURI(properties);
        var COMPANY_SERVER_URI = createCompanyServerURI(properties);

        var TRAVEL_AGENCY_HOST = properties.getProperty("travel-agency.host");
        var TRAVEL_AGENCY_PORT = Integer.parseInt(properties.getProperty("travel-agency.port"));

        var routingBuilder = HttpRouting.builder();

        routingBuilder.get("/whoami", (req, res) -> {
            res.send("Current thread: " + Thread.currentThread());
        });

        Cities.registerCities(routingBuilder);

        // FIXME: Start of the spaghetti code
        routingBuilder.post("/travel", (req, res) -> {
            // FIXME: Extracting the requested flight
            var queryFlight = queriedFlightFrom(req);
            var destinationCity = queryFlight.to();
            // FIXME: Company for loop
            Predicate<StructuredTaskScope.Subtask<? extends CompanyWeatherResponse>>
                    subtaskPredicate =

                    subtask -> switch(subtask.state()) {
                        case SUCCESS -> switch (subtask.get()) {
                            case CompanyResponse _ -> true; // cancels the scope
                            case WeatherResponse _ -> false;
                        };
                        case FAILED ->
                                throw new IllegalStateException("Got a subtask in FAILED state");
                        case UNAVAILABLE ->
                                throw new IllegalStateException("Got a subtask in UNAVAILABLE state");
                    };

            try (var travelScope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.allUntil(subtaskPredicate)
            )) {

                // First submit your tasks as usual
                var companyResponseSubTask =
                        travelScope.fork(() -> queryCompanyServer(COMPANY_SERVER_URI, queryFlight));
                var weatherResponseSubTask =
                        travelScope.fork(() -> queryWeatherServer(WEATHER_SERVER_URI, destinationCity));

                // then call join
                travelScope.join();

                // then analyze the results
                var companyResponse = companyResponseSubTask.get();
                var weatherResponse = switch(weatherResponseSubTask.state()) {
                    case SUCCESS ->
                            weatherResponseSubTask.get();
                    case UNAVAILABLE ->
                            new WeatherResponse.WeatherTimeout("Weather forecast took too long");
                    case FAILED ->
                            throw new IllegalStateException("Got a weather subtask in FAILED state");
                };
                var travelResponse = buildResponse(companyResponse, weatherResponse);
                res.status(Status.OK_200).send(travelResponse);
            }
        });
        // FIXME: Handler end

        WebServer webServer = WebServer.builder()
                .address(InetAddress.getLocalHost())
                .host(TRAVEL_AGENCY_HOST).port(TRAVEL_AGENCY_PORT)
                .addFeature(
                        StaticContentFeature.builder()
                                .addClasspath(b -> b.location("/static-content").welcome("index.html").context("/"))
                                .build())
                .routing(routingBuilder)
                .mediaContext(MediaContext.builder()
                        .mediaSupportsDiscoverServices(false)
                        .addMediaSupport(JsonpSupport.create())
                        .addMediaSupport(JsonbSupport.create(Config.empty()))
                        .build())
                .build();

        webServer.start();
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static ClientUri createCompanyServerURI(Properties properties) {
        var COMPANY_SERVER_HOST = properties.getProperty("companies.host");
        var COMPANY_SERVER_PORT = Integer.parseInt(properties.getProperty("companies.port"));
        var companyServerUriInfo = UriInfo.builder()
                .host(COMPANY_SERVER_HOST)
                .port(COMPANY_SERVER_PORT)
                .build();
        var COMPANY_SERVER_URI = ClientUri.create(companyServerUriInfo);
        return COMPANY_SERVER_URI;
    }

    private static ClientUri createWeatherServerURI(Properties properties) {
        var WEATHER_SERVER_HOST = properties.getProperty("weather-agencies.host");
        var WEATHER_SERVER_PORT = Integer.parseInt(properties.getProperty("weather-agencies.port"));
        var weatherServerUriInfo = UriInfo.builder()
                .host(WEATHER_SERVER_HOST)
                .port(WEATHER_SERVER_PORT)
                .build();
        var WEATHER_SERVER_URI = ClientUri.create(weatherServerUriInfo);
        return WEATHER_SERVER_URI;
    }

    private static Properties readPropertiesFrom(String fileName) {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(fileName))) {
            properties.load(reader);
            return properties;
        } catch (IOException e) {
            IO.println("Error reading properties file " + e.getMessage());
            throw new RuntimeException(e);
        }
    }
}
