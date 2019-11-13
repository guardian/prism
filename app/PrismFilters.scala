import javax.inject.Inject
import play.api.http.DefaultHttpFilters
import play.filters.gzip.GzipFilter
import utils.JsonpFilter

class PrismFilters @Inject() (
  gzip: GzipFilter,
  jsonp: JsonpFilter
) extends DefaultHttpFilters(gzip, jsonp)