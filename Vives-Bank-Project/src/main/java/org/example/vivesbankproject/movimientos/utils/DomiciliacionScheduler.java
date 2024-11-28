package org.example.vivesbankproject.movimientos.utils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.vivesbankproject.cuenta.exceptions.cuenta.CuentaNotFound;
import org.example.vivesbankproject.cuenta.mappers.CuentaMapper;
import org.example.vivesbankproject.cuenta.services.CuentaService;
import org.example.vivesbankproject.movimientos.exceptions.domiciliacion.DomiciliacionException;
import org.example.vivesbankproject.movimientos.exceptions.domiciliacion.SaldoInsuficienteException;
import org.example.vivesbankproject.movimientos.mappers.MovimientoMapper;
import org.example.vivesbankproject.movimientos.models.Domiciliacion;
import org.example.vivesbankproject.movimientos.models.Movimiento;
import org.example.vivesbankproject.movimientos.repositories.DomiciliacionRepository;
import org.example.vivesbankproject.movimientos.repositories.MovimientosRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DomiciliacionScheduler {

    private final DomiciliacionRepository domiciliacionRepository;
    private final MovimientosRepository movimientosRepository;
    private final CuentaService cuentaService;
    private final CuentaMapper cuentaMapper;

    @Autowired
    public DomiciliacionScheduler(DomiciliacionRepository domiciliacionRepository, MovimientosRepository movimientosRepository, CuentaService cuentaService, CuentaMapper cuentaMapper, MovimientoMapper movimientosMapper) {
        this.domiciliacionRepository = domiciliacionRepository;
        this.movimientosRepository = movimientosRepository;
        this.cuentaService = cuentaService;
        this.cuentaMapper = cuentaMapper;
    }

    @Scheduled(cron = "0 0 0 * * ?") // Ejecución diaria a medianoche
    public void procesarDomiciliaciones() {
        log.info("Procesando domiciliaciones periódicas");

        LocalDateTime ahora = LocalDateTime.now();

        // Filtrar domiciliaciones activas que requieren ejecución
        List<Domiciliacion> domiciliaciones = domiciliacionRepository.findAll().stream()
                .filter(d -> d.getActiva() &&
                        d.getUltimaEjecucion().plusMonths(1).isBefore(ahora))
                .collect(Collectors.toList());

        for (Domiciliacion domiciliacion : domiciliaciones) {
            try {
                // Validar y ejecutar la domiciliación
                ejecutarDomiciliacion(domiciliacion);

                // Actualizar última ejecución
                domiciliacion.setUltimaEjecucion(ahora);
                domiciliacionRepository.save(domiciliacion);
            } catch (SaldoInsuficienteException ex) {
                log.warn("Saldo insuficiente para domiciliación: {}", domiciliacion.getGuid());
            } catch (DomiciliacionException ex) {
                log.error("Error al procesar domiciliación: {}", domiciliacion.getGuid(), ex);
            }
        }
    }

    private void ejecutarDomiciliacion(Domiciliacion domiciliacion) {
        var cuentaOrigen = cuentaService.getByIban(domiciliacion.getIbanOrigen());

        BigDecimal saldoActual = new BigDecimal(cuentaOrigen.getSaldo());
        BigDecimal cantidad = new BigDecimal(String.valueOf(domiciliacion.getCantidad()));

        // Validar saldo suficiente
        if (saldoActual.compareTo(cantidad) < 0) {
            throw new SaldoInsuficienteException(cuentaOrigen.getIban(), saldoActual);
        }

        // Actualizar saldos
        cuentaOrigen.setSaldo(saldoActual.subtract(cantidad).toString());

        cuentaService.update(cuentaOrigen.getGuid(), cuentaMapper.toCuentaRequestUpdate(cuentaOrigen));

        // Registrar el movimiento
        Movimiento movimiento = Movimiento.builder()
                .domiciliacion(domiciliacion)
                .build();

        movimientosRepository.save(movimiento);
    }
}
