# golang through the Harbor cache (the servers cannot reach Docker Hub); distroless is pulled from gcr.io.
FROM registry.starsdom.com:9443/docker.io/library/golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY *.go ./
COPY api/ api/
RUN CGO_ENABLED=0 go build -o /api .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /api /api
ENTRYPOINT ["/api"]
